package com.group41.backend.schedule.service;

import com.group41.backend.schedule.service.GoogleCalendarApi.RawCalendar;
import com.group41.backend.common.ApiException;
import com.group41.backend.schedule.controller.ScheduleDtos;
import com.group41.backend.schedule.domain.GoogleCredential;
import com.group41.backend.schedule.domain.ScheduleSource;
import com.group41.backend.schedule.domain.TimeBlock;
import com.group41.backend.schedule.repository.GoogleCredentialRepository;
import com.group41.backend.schedule.service.GoogleCalendarApi.RawEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Sincroniza el calendario de Google de un usuario en bloques de tiempo.
 *
 * Esta clase NO es transaccional a proposito: hace llamadas de red a Google, y
 * una transaccion abierta durante esa espera retendria una conexion de la base
 * de datos. Solo el reemplazo final de bloques es transaccional.
 */
@Service
public class GoogleCalendarService {

    private static final int MAX_BLOCKS = 20_000;
    private static final int MAX_TITLE_LENGTH = 300;
    private static final int MAX_SOURCE_ID_LENGTH = 512;

    private final GoogleCalendarApi api;
    private final GoogleCredentialRepository credentialRepository;
    private final TokenCrypto crypto;
    private final ScheduleService scheduleService;
    private final boolean credentialsPresent;
    private final long pastDays;
    private final long futureDays;

    public GoogleCalendarService(
            GoogleCalendarApi api,
            GoogleCredentialRepository credentialRepository,
            TokenCrypto crypto,
            ScheduleService scheduleService,
            @Value("${app.google.client-id:}") String clientId,
            @Value("${app.google.client-secret:}") String clientSecret,
            @Value("${app.schedule.google-past-days:30}") long pastDays,
            @Value("${app.schedule.google-future-days:180}") long futureDays
    ) {
        this.api = api;
        this.credentialRepository = credentialRepository;
        this.crypto = crypto;
        this.scheduleService = scheduleService;
        this.credentialsPresent = !clientId.isBlank() && !clientSecret.isBlank();
        this.pastDays = pastDays;
        this.futureDays = futureDays;
    }

    /** Primera conexion: canjea el codigo del movil, guarda el refresh token y sincroniza. */
    public ScheduleDtos.GoogleSyncResponse connect(UUID userId, String authCode, String timezone) {
        requireConfigured();
        ZoneId zone = scheduleService.resolveZone(timezone);

        GoogleCalendarApi.Tokens tokens;
        try {
            tokens = api.exchangeAuthCode(authCode.trim());
        } catch (GoogleAuthException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "El codigo de Google es invalido o ya expiro");
        }

        String refreshToken = tokens.refreshToken();
        if (refreshToken == null || refreshToken.isBlank()) {
            // Google solo lo entrega la primera vez. Si ya hay uno guardado, sirve.
            if (!credentialRepository.existsByUserId(userId)) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "Google no entrego un refresh token: la app debe pedir acceso offline (serverAuthCode)");
            }
        } else {
            saveRefreshToken(userId, refreshToken);
        }

        return importEvents(userId, tokens.accessToken(), zone);
    }

    /** Resincroniza con el refresh token guardado, sin intervencion del movil. */
    public ScheduleDtos.GoogleSyncResponse resync(UUID userId, String timezone) {
        requireConfigured();
        ZoneId zone = scheduleService.resolveZone(timezone);

        GoogleCredential credential = credentialRepository.findByUserId(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                        "Aun no has conectado Google Calendar"));

        String accessToken;
        try {
            String refreshToken = crypto.decrypt(credential.getEncryptedRefreshToken());
            accessToken = api.refreshAccessToken(refreshToken);
        } catch (GoogleAuthException | IllegalStateException ex) {
            // Revocado en Google, o guardado con una clave que ya no es la actual:
            // no sirve de nada conservarlo, hay que conectar de nuevo.
            credentialRepository.delete(credential);
            throw new ApiException(HttpStatus.CONFLICT,
                    "Se perdio el acceso a Google Calendar: conectalo de nuevo");
        }

        return importEvents(userId, accessToken, zone);
    }

    private ScheduleDtos.GoogleSyncResponse importEvents(UUID userId, String accessToken, ZoneId zone) {
        Instant now = Instant.now();
        Instant from = now.minus(pastDays, ChronoUnit.DAYS);
        Instant to = now.plus(futureDays, ChronoUnit.DAYS);

        List<RawCalendar> calendars = api.listCalendars(accessToken).stream()
                .filter(GoogleCalendarService::isRelevant)
                .toList();
        if (calendars.isEmpty()) {
            // El principal siempre existe: si la lista llega vacia, mejor eso que borrar el horario.
            calendars = List.of(new RawCalendar("primary", "primary", true, true));
        }

        List<TimeBlock> blocks = new ArrayList<>();
        int skipped = 0;
        for (RawCalendar calendar : calendars) {
            for (RawEvent event : api.listEvents(accessToken, calendar.id(), from, to)) {
                try {
                    TimeBlock block = toBlock(userId, calendar.id(), event, zone);
                    if (block != null) {
                        blocks.add(block);
                    }
                } catch (RuntimeException ex) {
                    // Un evento raro no debe tumbar la sincronizacion de todo el calendario.
                    skipped++;
                }
            }
        }

        if (blocks.size() > MAX_BLOCKS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Tus calendarios tienen demasiados eventos");
        }

        scheduleService.replaceBlocks(userId, ScheduleSource.GOOGLE_CALENDAR, blocks);
        return new ScheduleDtos.GoogleSyncResponse(blocks.size(), skipped, calendars.size());
    }

    /**
     * Cuales calendarios cuentan como "mi tiempo ocupado": el principal y los que el
     * usuario tiene visibles. Festivos, cumpleanos y clima son eventos de dia completo
     * que marcarian el dia entero como ocupado.
     */
    private static boolean isRelevant(RawCalendar calendar) {
        String id = calendar.id();
        if (id == null) {
            return false;
        }
        if (id.endsWith("#holiday@group.v.calendar.google.com")
                || id.endsWith("#contacts@group.v.calendar.google.com")
                || id.endsWith("#weather@group.v.calendar.google.com")) {
            return false;
        }
        return calendar.primary() || calendar.selected();
    }

    /** Devuelve null si el evento no ocupa tiempo. Lanza si no se pudo interpretar. */
    private static TimeBlock toBlock(UUID userId, String calendarId, RawEvent event, ZoneId zone) {
        if ("cancelled".equalsIgnoreCase(event.status())
                || "transparent".equalsIgnoreCase(event.transparency())
                || "workingLocation".equals(event.eventType())
                || event.declinedBySelf()) {
            return null;
        }

        ZonedDateTime from;
        ZonedDateTime to;

        if (event.startDate() != null) {
            // Dia completo: la fecha de fin que da Google es exclusiva.
            LocalDate first = LocalDate.parse(event.startDate());
            LocalDate lastExclusive = event.endDate() != null
                    ? LocalDate.parse(event.endDate())
                    : first.plusDays(1);
            if (!lastExclusive.isAfter(first)) {
                lastExclusive = first.plusDays(1);
            }
            from = first.atStartOfDay(zone);
            to = lastExclusive.atStartOfDay(zone);
        } else {
            ZoneId eventZone = event.timeZone() != null ? ZoneId.of(event.timeZone()) : zone;
            from = parseDateTime(event.startDateTime(), eventZone).withZoneSameInstant(zone);
            to = parseDateTime(event.endDateTime(), eventZone).withZoneSameInstant(zone);
        }

        if (!to.isAfter(from)) {
            return null; // sin duracion
        }

        return new TimeBlock(userId, titleOf(event), from, to,
                ScheduleSource.GOOGLE_CALENDAR, sourceId(calendarId, event, from));
    }

    /** Google manda offset; si por algo no viene, se interpreta en la zona del evento. */
    private static ZonedDateTime parseDateTime(String text, ZoneId fallbackZone) {
        try {
            return OffsetDateTime.parse(text).toZonedDateTime();
        } catch (DateTimeParseException ex) {
            return LocalDateTime.parse(text).atZone(fallbackZone);
        }
    }

    private static String titleOf(RawEvent event) {
        String title = event.summary();
        if (title == null || title.isBlank()) {
            return "Evento";
        }
        return title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH) : title;
    }

    /** Calendario + evento + inicio: el id de un evento solo es unico dentro de su calendario. */
    private static String sourceId(String calendarId, RawEvent event, ZonedDateTime start) {
        String id = calendarId + "/" + (event.id() != null ? event.id() : "sin-id") + "#" + start.toInstant();
        return id.length() > MAX_SOURCE_ID_LENGTH ? id.substring(0, MAX_SOURCE_ID_LENGTH) : id;
    }

    private void saveRefreshToken(UUID userId, String refreshToken) {
        String encrypted = crypto.encrypt(refreshToken);
        GoogleCredential credential = credentialRepository.findByUserId(userId).orElse(null);
        if (credential == null) {
            credential = new GoogleCredential(userId, encrypted);
        } else {
            credential.setEncryptedRefreshToken(encrypted);
        }
        credentialRepository.save(credential);
    }

    private void requireConfigured() {
        if (!credentialsPresent || !crypto.isAvailable()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Google Calendar no esta configurado en el servidor");
        }
    }
}