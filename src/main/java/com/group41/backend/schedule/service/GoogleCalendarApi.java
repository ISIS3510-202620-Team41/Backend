package com.group41.backend.schedule.service;

import java.time.Instant;
import java.util.List;

/** Lo que el backend necesita de Google. La implementacion real habla HTTP; los tests la simulan. */
public interface GoogleCalendarApi {

    /** refreshToken puede ser null: Google solo lo entrega en el primer consentimiento. */
    record Tokens(String accessToken, String refreshToken) {
    }

    /**
     * Un calendario de la lista del usuario.
     *
     * @param selected si el usuario lo tiene visible en su Google Calendar
     */
    record RawCalendar(String id, String name, boolean primary, boolean selected) {
    }

    /** Un evento tal como lo entrega Google, sin interpretar. */
    record RawEvent(
            String id,
            String summary,
            String status,
            String transparency,
            String eventType,
            boolean declinedBySelf,
            String startDateTime,
            String startDate,
            String endDateTime,
            String endDate,
            String timeZone
    ) {
    }

    /** @throws GoogleAuthException si el codigo es invalido o expiro */
    Tokens exchangeAuthCode(String authCode);

    /** @throws GoogleAuthException si el usuario revoco el acceso */
    String refreshAccessToken(String refreshToken);

    /** Calendarios del usuario. Requiere el permiso calendar.calendarlist.readonly. */
    List<RawCalendar> listCalendars(String accessToken);

    List<RawEvent> listEvents(String accessToken, String calendarId, Instant from, Instant to);
}