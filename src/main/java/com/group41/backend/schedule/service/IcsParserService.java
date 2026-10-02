package com.group41.backend.schedule.service;

import com.group41.backend.common.ApiException;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.DateTime;
import net.fortuna.ical4j.model.Period;
import net.fortuna.ical4j.model.PeriodList;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.DtEnd;
import net.fortuna.ical4j.model.property.DtStart;
import net.fortuna.ical4j.model.property.RecurrenceId;
import net.fortuna.ical4j.model.property.Summary;
import net.fortuna.ical4j.model.property.Uid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Convierte un archivo .ics en intervalos ocupados.
 *
 * Las recurrencias (clases semanales, etc.) se expanden dentro de una ventana
 * acotada: de unos dias atras a unos meses adelante. Expandir "para siempre"
 * generaria miles de bloques que nadie va a consultar.
 */
@Service
public class IcsParserService {

    private static final int MAX_BLOCKS = 20_000;
    private static final int MAX_TITLE_LENGTH = 300;

    private final long pastDays;
    private final long futureDays;

    public IcsParserService(
            @Value("${app.schedule.ics-past-days:30}") long pastDays,
            @Value("${app.schedule.ics-future-days:180}") long futureDays
    ) {
        this.pastDays = pastDays;
        this.futureDays = futureDays;
    }

    public record ParsedEvent(String uid, String title, ZonedDateTime start, ZonedDateTime end) {
    }

    /**
     * @param skippedEvents          eventos que no se pudieron procesar
     * @param unsupportedRecurrences eventos de dia completo que se repiten: solo entra la primera vez
     */
    public record ParsedCalendar(List<ParsedEvent> events, int skippedEvents, int unsupportedRecurrences) {
    }

    private static final class Counters {
        int skipped;
        int unsupported;
    }

    /**
     * @param zone zona del usuario: se usa para eventos de dia completo y para
     *             horas "flotantes" (sin zona), que no dicen en que zona estan.
     */
    public ParsedCalendar parse(byte[] content, ZoneId zone) {
        String text = new String(content, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        if (!text.stripLeading().regionMatches(true, 0, "BEGIN:VCALENDAR", 0, 15)) {
            throw notACalendar();
        }

        Calendar calendar;
        try {
            calendar = new CalendarBuilder().build(new StringReader(text));
        } catch (IOException | ParserException | RuntimeException ex) {
            throw notACalendar();
        }

        List<VEvent> events = new ArrayList<>();
        for (Object component : calendar.getComponents(Component.VEVENT)) {
            if (component instanceof VEvent event) {
                events.add(event);
            }
        }

        // Instancias de una serie que el usuario movio o edito: en el .ics aparecen
        // como un VEVENT aparte con RECURRENCE-ID. Hay que quitar la original.
        Map<String, Set<Long>> overridden = new HashMap<>();
        for (VEvent event : events) {
            RecurrenceId recurrenceId = event.getRecurrenceId();
            if (recurrenceId != null && recurrenceId.getDate() != null) {
                overridden.computeIfAbsent(uidOf(event), k -> new HashSet<>())
                        .add(recurrenceId.getDate().getTime());
            }
        }

        Instant now = Instant.now();
        Instant windowStart = now.minus(pastDays, ChronoUnit.DAYS);
        Instant windowEnd = now.plus(futureDays, ChronoUnit.DAYS);

        List<ParsedEvent> result = new ArrayList<>();
        Counters counters = new Counters();

        for (VEvent event : events) {
            try {
                expand(event, overridden, zone, windowStart, windowEnd, result, counters);
            } catch (RuntimeException ex) {
                // Un evento raro no debe tumbar la importacion de todo el calendario.
                counters.skipped++;
            }
            if (result.size() > MAX_BLOCKS) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "El calendario tiene demasiados eventos");
            }
        }

        return new ParsedCalendar(result, counters.skipped, counters.unsupported);
    }

    private void expand(VEvent event, Map<String, Set<Long>> overridden, ZoneId zone,
                        Instant windowStart, Instant windowEnd,
                        List<ParsedEvent> out, Counters counters) {

        // Cancelado no ocupa tiempo. TRANSPARENT significa "no me marques como ocupado".
        if (hasValue(event.getStatus(), "CANCELLED") || hasValue(event.getTransparency(), "TRANSPARENT")) {
            return;
        }

        DtStart start = event.getStartDate();
        if (start == null) {
            return;
        }

        String uid = uidOf(event);
        String title = titleOf(event);

        // Evento de dia completo (DTSTART con VALUE=DATE).
        if (!(start.getDate() instanceof DateTime)) {
            expandAllDay(event, start, zone, windowStart, windowEnd, uid, title, out, counters);
            return;
        }

        boolean floating = isFloating((DateTime) start.getDate());
        PeriodList periods = event.calculateRecurrenceSet(new Period(utc(windowStart), utc(windowEnd)));

        // Solo la serie original se limpia: el override es un evento normal con su propia hora.
        Set<Long> skipStarts = event.getRecurrenceId() == null
                ? overridden.getOrDefault(uid, Set.of())
                : Set.of();

        for (Period period : periods) {
            if (skipStarts.contains(period.getStart().getTime())) {
                continue;
            }
            ZonedDateTime from = toZoned(period.getStart().toInstant(), floating, zone);
            ZonedDateTime to = toZoned(period.getEnd().toInstant(), floating, zone);
            if (!to.isAfter(from)) {
                continue; // evento sin duracion
            }
            out.add(new ParsedEvent(uid, title, from, to));
        }
    }

    private void expandAllDay(VEvent event, DtStart start, ZoneId zone,
                              Instant windowStart, Instant windowEnd,
                              String uid, String title,
                              List<ParsedEvent> out, Counters counters) {
        LocalDate first = LocalDate.parse(start.getValue(), DateTimeFormatter.BASIC_ISO_DATE);

        DtEnd end = event.getEndDate(false);
        LocalDate lastExclusive = end != null
                ? LocalDate.parse(end.getValue(), DateTimeFormatter.BASIC_ISO_DATE)
                : first.plusDays(1);
        if (!lastExclusive.isAfter(first)) {
            lastExclusive = first.plusDays(1);
        }

        if (event.getProperty(Property.RRULE) != null) {
            counters.unsupported++;
        }

        ZonedDateTime from = first.atStartOfDay(zone);
        ZonedDateTime to = lastExclusive.atStartOfDay(zone);
        if (to.toInstant().isAfter(windowStart) && from.toInstant().isBefore(windowEnd)) {
            out.add(new ParsedEvent(uid, title, from, to));
        }
    }

    /**
     * Hora sin "Z" ni TZID. ical4j la interpreta en la zona de la JVM, que en un
     * servidor casi nunca es la del usuario. Se recupera la hora de reloj y se
     * reinterpreta en la zona del usuario.
     */
    private static ZonedDateTime toZoned(Instant instant, boolean floating, ZoneId zone) {
        if (floating) {
            return instant.atZone(ZoneId.systemDefault()).toLocalDateTime().atZone(zone);
        }
        return instant.atZone(zone);
    }

    private static boolean isFloating(DateTime dateTime) {
        return !dateTime.isUtc() && dateTime.getTimeZone() == null;
    }

    private static DateTime utc(Instant instant) {
        DateTime dateTime = new DateTime(Date.from(instant));
        dateTime.setUtc(true);
        return dateTime;
    }

    private static boolean hasValue(Property property, String value) {
        return property != null && value.equalsIgnoreCase(property.getValue());
    }

    private static String uidOf(VEvent event) {
        Uid uid = event.getUid();
        return uid != null && uid.getValue() != null ? uid.getValue() : "sin-uid";
    }

    private static String titleOf(VEvent event) {
        Summary summary = event.getSummary();
        String title = summary != null ? summary.getValue() : null;
        if (title == null || title.isBlank()) {
            return "Evento";
        }
        return title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH) : title;
    }

    private static ApiException notACalendar() {
        return new ApiException(HttpStatus.BAD_REQUEST, "El archivo no es un calendario .ics valido");
    }
}