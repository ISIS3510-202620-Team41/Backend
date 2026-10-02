package com.group41.backend.schedule.service;

import com.group41.backend.common.ApiException;
import com.group41.backend.schedule.controller.ScheduleDtos;
import com.group41.backend.schedule.domain.ScheduleSource;
import com.group41.backend.schedule.domain.TimeBlock;
import com.group41.backend.schedule.repository.TimeBlockRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class ScheduleService {

    private static final int MAX_SOURCE_ID_LENGTH = 512;

    private final TimeBlockRepository timeBlockRepository;
    private final IcsParserService icsParserService;
    private final ZoneId defaultZone;
    private final LocalTime dayStart;
    private final LocalTime dayEnd;

    public ScheduleService(
            TimeBlockRepository timeBlockRepository,
            IcsParserService icsParserService,
            @Value("${app.schedule.default-timezone:America/Bogota}") String defaultTimezone,
            @Value("${app.schedule.day-start:06:00}") String dayStart,
            @Value("${app.schedule.day-end:22:00}") String dayEnd
    ) {
        this.timeBlockRepository = timeBlockRepository;
        this.icsParserService = icsParserService;
        // Si la configuracion esta mal, mejor fallar al arrancar que calcular huecos raros.
        this.defaultZone = ZoneId.of(defaultTimezone);
        this.dayStart = LocalTime.parse(dayStart);
        this.dayEnd = LocalTime.parse(dayEnd);
        if (!this.dayEnd.isAfter(this.dayStart)) {
            throw new IllegalStateException("app.schedule.day-end debe ser posterior a app.schedule.day-start");
        }
    }

    /** Intervalos libres del usuario en un dia, dentro de la ventana configurada. */
    @Transactional(readOnly = true)
    public ScheduleDtos.GapsResponse gapsFor(UUID userId, LocalDate date, String timezone) {
        ZoneId zone = resolveZone(timezone);

        // "El dia" depende de la zona: el mismo instante puede caer en fechas distintas.
        ZonedDateTime windowStart = date.atTime(dayStart).atZone(zone);
        ZonedDateTime windowEnd = date.atTime(dayEnd).atZone(zone);

        List<GapCalculator.Interval> busy = timeBlockRepository
                .findOverlapping(userId, windowStart, windowEnd).stream()
                .map(t -> new GapCalculator.Interval(t.getStartTime().toInstant(), t.getEndTime().toInstant()))
                .toList();

        List<ScheduleDtos.FreeInterval> free = GapCalculator
                .freeIntervals(windowStart.toInstant(), windowEnd.toInstant(), busy).stream()
                .map(i -> new ScheduleDtos.FreeInterval(
                        format(i.start().atZone(zone)),
                        format(i.end().atZone(zone))))
                .toList();

        return new ScheduleDtos.GapsResponse(date.toString(), zone.getId(), free);
    }

    /**
     * Reemplaza los bloques ICS del usuario por los del archivo.
     *
     * El archivo se parsea ANTES de borrar nada: si viene roto, el usuario
     * conserva el horario que ya tenia.
     */
    @Transactional
    public ScheduleDtos.IcsImportResponse importIcs(UUID userId, MultipartFile file, String timezone) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "No se recibio ningun archivo");
        }

        ZoneId zone = resolveZone(timezone);

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "No se pudo leer el archivo");
        }

        IcsParserService.ParsedCalendar parsed = icsParserService.parse(content, zone);

        List<TimeBlock> blocks = parsed.events().stream()
                .map(e -> new TimeBlock(
                        userId,
                        e.title(),
                        e.start(),
                        e.end(),
                        ScheduleSource.ICS,
                        sourceId(e)))
                .toList();

        timeBlockRepository.deleteByUserIdAndSource(userId, ScheduleSource.ICS);
        timeBlockRepository.saveAll(blocks);

        return new ScheduleDtos.IcsImportResponse(
                blocks.size(), parsed.skippedEvents(), parsed.unsupportedRecurrences());
    }

    /** Reemplaza todos los bloques de un origen por los nuevos, en una sola transaccion. */
    @Transactional
    public void replaceBlocks(UUID userId, ScheduleSource source, List<TimeBlock> blocks) {
        timeBlockRepository.deleteByUserIdAndSource(userId, source);
        timeBlockRepository.saveAll(blocks);
    }

    /** De los usuarios dados, cuales tienen un bloque activo en ese instante. */
    @Transactional(readOnly = true)
    public Set<UUID> busyUserIds(Collection<UUID> userIds, ZonedDateTime at) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(timeBlockRepository.findBusyUserIds(userIds, at));
    }

    public ZoneId resolveZone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return defaultZone;
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (DateTimeException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Zona horaria invalida: " + timezone);
        }
    }

    /** UID del evento + inicio de la ocurrencia: distingue cada repeticion de una serie. */
    private static String sourceId(IcsParserService.ParsedEvent event) {
        String id = event.uid() + "#" + event.start().toInstant();
        return id.length() > MAX_SOURCE_ID_LENGTH ? id.substring(0, MAX_SOURCE_ID_LENGTH) : id;
    }

    private static String format(ZonedDateTime dateTime) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(dateTime);
    }
}