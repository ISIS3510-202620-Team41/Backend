package com.group41.backend;

import com.group41.backend.schedule.service.GoogleCalendarApi.RawCalendar;
import com.group41.backend.schedule.service.RestGoogleCalendarApi.CalendarPage;
import com.group41.backend.schedule.service.GoogleCalendarApi.RawEvent;
import com.group41.backend.schedule.service.RestGoogleCalendarApi;
import com.group41.backend.schedule.service.RestGoogleCalendarApi.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Google Calendar - lectura de la respuesta de events.list")
class GoogleEventsParsingTests {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    @DisplayName("interpreta eventos con hora, de dia completo y rechazados, e ignora campos desconocidos")
    void parsesEventsAndNextPageToken() throws Exception {
        String json = """
                {
                  "kind": "calendar#events",
                  "nextPageToken": "pagina-2",
                  "items": [
                    {
                      "id": "e1", "status": "confirmed", "summary": "Calculo",
                      "start": {"dateTime": "2026-10-05T10:00:00-05:00", "timeZone": "America/Bogota"},
                      "end": {"dateTime": "2026-10-05T12:00:00-05:00"}
                    },
                    {
                      "id": "e2", "summary": "Festivo", "transparency": "transparent",
                      "start": {"date": "2026-10-12"},
                      "end": {"date": "2026-10-13"}
                    },
                    {
                      "id": "e3", "summary": "Reunion",
                      "attendees": [{"email": "yo@test.com", "self": true, "responseStatus": "declined"}],
                      "start": {"dateTime": "2026-10-06T09:00:00-05:00"},
                      "end": {"dateTime": "2026-10-06T10:00:00-05:00"}
                    }
                  ]
                }
                """;

        Page page = RestGoogleCalendarApi.parsePage(mapper.readTree(json));

        assertThat(page.nextPageToken()).isEqualTo("pagina-2");
        assertThat(page.events()).hasSize(3);

        RawEvent timed = page.events().get(0);
        assertThat(timed.id()).isEqualTo("e1");
        assertThat(timed.startDateTime()).isEqualTo("2026-10-05T10:00:00-05:00");
        assertThat(timed.timeZone()).isEqualTo("America/Bogota");
        assertThat(timed.declinedBySelf()).isFalse();

        RawEvent allDay = page.events().get(1);
        assertThat(allDay.startDate()).isEqualTo("2026-10-12");
        assertThat(allDay.startDateTime()).isNull();
        assertThat(allDay.transparency()).isEqualTo("transparent");

        assertThat(page.events().get(2).declinedBySelf()).isTrue();
    }

    @Test
    @DisplayName("una respuesta sin eventos da una pagina vacia y sin siguiente")
    void emptyResponse() throws Exception {
        Page page = RestGoogleCalendarApi.parsePage(mapper.readTree("{}"));

        assertThat(page.events()).isEmpty();
        assertThat(page.nextPageToken()).isNull();
    }

        @Test
    @DisplayName("interpreta la lista de calendarios: nombre, principal y visibilidad")
    void parsesCalendarList() throws Exception {
        String json = """
                {
                  "kind": "calendar#calendarList",
                  "items": [
                    {"id": "yo@gmail.com", "summary": "yo@gmail.com", "primary": true, "selected": true},
                    {"id": "abc123@group.calendar.google.com", "summary": "Personal", "selected": true},
                    {"id": "xyz@group.calendar.google.com", "summary": "Original", "summaryOverride": "Gym"}
                  ]
                }
                """;

        CalendarPage page = RestGoogleCalendarApi.parseCalendarPage(mapper.readTree(json));

        assertThat(page.nextPageToken()).isNull();
        assertThat(page.calendars()).hasSize(3);

        RawCalendar primary = page.calendars().get(0);
        assertThat(primary.primary()).isTrue();
        assertThat(primary.selected()).isTrue();

        RawCalendar personal = page.calendars().get(1);
        assertThat(personal.name()).isEqualTo("Personal");
        assertThat(personal.primary()).isFalse();

        RawCalendar renamed = page.calendars().get(2);
        assertThat(renamed.name()).isEqualTo("Gym");
        assertThat(renamed.selected()).isFalse();
    }
}