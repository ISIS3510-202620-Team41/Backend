package com.group41.backend;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Horarios - importacion de .ics")
class ScheduleIcsTests {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final String TZ = "TZID=America/Bogota";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static int counter = 0;
    private String token;
    private String otherToken;
    private LocalDate day;

    @BeforeEach
    void setUp() throws Exception {
        token = register();
        otherToken = register();
        // Siempre dentro de la ventana de expansion, sin importar cuando se corran los tests.
        day = LocalDate.now(BOGOTA).plusDays(3);
    }

    private String register() throws Exception {
        String email = "ics" + (++counter) + "@test.com";
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"micontrasena123","name":"Test User"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asString();
    }

    /** yyyyMMdd, el formato de fecha de iCalendar. */
    private static String d(LocalDate date) {
        return date.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    private static String vevent(String uid, String... lines) {
        return "BEGIN:VEVENT\r\nUID:" + uid + "\r\nDTSTAMP:20260101T000000Z\r\n"
                + String.join("\r\n", lines) + "\r\nEND:VEVENT\r\n";
    }

    private static String calendar(String... events) {
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//ES\r\n"
                + String.join("", events) + "END:VCALENDAR\r\n";
    }

    private ResultActions importIcs(String bearer, String content) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "calendario.ics", "text/calendar", content.getBytes(StandardCharsets.UTF_8));
        return mockMvc.perform(multipart("/api/schedules/sync/ics")
                .file(file)
                .header("Authorization", "Bearer " + bearer));
    }

    private ResultActions gaps(String bearer, LocalDate date) throws Exception {
        return mockMvc.perform(get("/api/schedules/me/gaps")
                .param("date", date.toString())
                .header("Authorization", "Bearer " + bearer));
    }

    private String at(LocalDate date, String time) {
        return date + "T" + time + "-05:00";
    }

    @Test
    @DisplayName("importar exige token")
    void importRequiresAuth() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "c.ics", "text/calendar", calendar().getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/schedules/sync/ics").file(file))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("un texto cualquiera, un archivo vacio o la ausencia de archivo dan 400")
    void rejectsInvalidFiles() throws Exception {
        importIcs(token, "esto no es un calendario").andExpect(status().isBadRequest());
        importIcs(token, "").andExpect(status().isBadRequest());

        mockMvc.perform(multipart("/api/schedules/sync/ics")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("eventos con TZID y en UTC bloquean la hora correcta; un VALARM no estorba")
    void timedEventsBlockTheRightHours() throws Exception {
        String content = calendar(
                vevent("clase-1",
                        "SUMMARY:Calculo",
                        "DTSTART;" + TZ + ":" + d(day) + "T100000",
                        "DTEND;" + TZ + ":" + d(day) + "T120000",
                        "BEGIN:VALARM", "TRIGGER:-PT15M", "ACTION:DISPLAY", "DESCRIPTION:Recordatorio", "END:VALARM"),
                // 19:00-21:00 UTC = 14:00-16:00 en Bogota
                vevent("clase-2",
                        "SUMMARY:Fisica",
                        "DTSTART:" + d(day) + "T190000Z",
                        "DTEND:" + d(day) + "T210000Z"));

        importIcs(token, content)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(2))
                .andExpect(jsonPath("$.skippedEvents").value(0));

        gaps(token, day)
                .andExpect(jsonPath("$.free.length()").value(3))
                .andExpect(jsonPath("$.free[0].start").value(at(day, "06:00:00")))
                .andExpect(jsonPath("$.free[0].end").value(at(day, "10:00:00")))
                .andExpect(jsonPath("$.free[1].start").value(at(day, "12:00:00")))
                .andExpect(jsonPath("$.free[1].end").value(at(day, "14:00:00")))
                .andExpect(jsonPath("$.free[2].start").value(at(day, "16:00:00")))
                .andExpect(jsonPath("$.free[2].end").value(at(day, "22:00:00")));
    }

    @Test
    @DisplayName("una clase semanal se expande y respeta EXDATE")
    void weeklyRecurrenceHonorsExdate() throws Exception {
        String content = calendar(vevent("semanal",
                "SUMMARY:Programacion",
                "DTSTART;" + TZ + ":" + d(day) + "T140000",
                "DTEND;" + TZ + ":" + d(day) + "T160000",
                "RRULE:FREQ=WEEKLY;COUNT=4",
                "EXDATE;" + TZ + ":" + d(day.plusDays(14)) + "T140000"));

        // 4 ocurrencias menos la excluida.
        importIcs(token, content)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(3));

        gaps(token, day).andExpect(jsonPath("$.free.length()").value(2));
        gaps(token, day.plusDays(7)).andExpect(jsonPath("$.free.length()").value(2));
        gaps(token, day.plusDays(14)).andExpect(jsonPath("$.free.length()").value(1));
        gaps(token, day.plusDays(21)).andExpect(jsonPath("$.free.length()").value(2));
        // Fuera de la serie, el dia queda libre.
        gaps(token, day.plusDays(1)).andExpect(jsonPath("$.free.length()").value(1));
    }

    @Test
    @DisplayName("una instancia movida (RECURRENCE-ID) reemplaza a la original")
    void movedInstanceReplacesOriginal() throws Exception {
        LocalDate second = day.plusDays(7);

        String content = calendar(
                vevent("serie",
                        "SUMMARY:Laboratorio",
                        "DTSTART;" + TZ + ":" + d(day) + "T140000",
                        "DTEND;" + TZ + ":" + d(day) + "T160000",
                        "RRULE:FREQ=WEEKLY;COUNT=3"),
                // La segunda semana se movio de 14:00-16:00 a 18:00-19:00.
                vevent("serie",
                        "SUMMARY:Laboratorio (movido)",
                        "RECURRENCE-ID;" + TZ + ":" + d(second) + "T140000",
                        "DTSTART;" + TZ + ":" + d(second) + "T180000",
                        "DTEND;" + TZ + ":" + d(second) + "T190000"));

        importIcs(token, content)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(3));

        gaps(token, second)
                .andExpect(jsonPath("$.free.length()").value(2))
                .andExpect(jsonPath("$.free[0].start").value(at(second, "06:00:00")))
                .andExpect(jsonPath("$.free[0].end").value(at(second, "18:00:00")))
                .andExpect(jsonPath("$.free[1].start").value(at(second, "19:00:00")))
                .andExpect(jsonPath("$.free[1].end").value(at(second, "22:00:00")));

        gaps(token, day)
                .andExpect(jsonPath("$.free[0].end").value(at(day, "14:00:00")));
    }

    @Test
    @DisplayName("los eventos cancelados y los 'transparentes' no ocupan tiempo")
    void cancelledAndTransparentEventsAreIgnored() throws Exception {
        String content = calendar(
                vevent("cancelado",
                        "DTSTART;" + TZ + ":" + d(day) + "T100000",
                        "DTEND;" + TZ + ":" + d(day) + "T120000",
                        "STATUS:CANCELLED"),
                vevent("libre",
                        "DTSTART;" + TZ + ":" + d(day) + "T120000",
                        "DTEND;" + TZ + ":" + d(day) + "T140000",
                        "TRANSP:TRANSPARENT"),
                vevent("real",
                        "DTSTART;" + TZ + ":" + d(day) + "T150000",
                        "DTEND;" + TZ + ":" + d(day) + "T160000"));

        importIcs(token, content)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1));

        gaps(token, day)
                .andExpect(jsonPath("$.free.length()").value(2))
                .andExpect(jsonPath("$.free[0].end").value(at(day, "15:00:00")))
                .andExpect(jsonPath("$.free[1].start").value(at(day, "16:00:00")));
    }

    @Test
    @DisplayName("un evento de dia completo ocupa todo el dia y solo ese dia")
    void allDayEventBlocksTheWholeDay() throws Exception {
        String content = calendar(vevent("festivo",
                "SUMMARY:Festivo",
                "DTSTART;VALUE=DATE:" + d(day),
                "DTEND;VALUE=DATE:" + d(day.plusDays(1))));

        importIcs(token, content).andExpect(status().isOk());

        gaps(token, day).andExpect(jsonPath("$.free.length()").value(0));
        gaps(token, day.plusDays(1)).andExpect(jsonPath("$.free.length()").value(1));
    }

    @Test
    @DisplayName("una hora sin zona se interpreta en la zona del usuario")
    void floatingTimesUseTheUserZone() throws Exception {
        String content = calendar(vevent("flotante",
                "DTSTART:" + d(day) + "T100000",
                "DTEND:" + d(day) + "T120000"));

        importIcs(token, content).andExpect(status().isOk());

        gaps(token, day)
                .andExpect(jsonPath("$.free.length()").value(2))
                .andExpect(jsonPath("$.free[0].end").value(at(day, "10:00:00")))
                .andExpect(jsonPath("$.free[1].start").value(at(day, "12:00:00")));
    }

    @Test
    @DisplayName("importar de nuevo reemplaza lo importado antes")
    void reimportReplacesPreviousBlocks() throws Exception {
        importIcs(token, calendar(vevent("a",
                "DTSTART;" + TZ + ":" + d(day) + "T100000",
                "DTEND;" + TZ + ":" + d(day) + "T120000")))
                .andExpect(status().isOk());

        importIcs(token, calendar(vevent("b",
                "DTSTART;" + TZ + ":" + d(day) + "T150000",
                "DTEND;" + TZ + ":" + d(day) + "T160000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1));

        // El bloque de las 10:00 desaparecio; solo queda el de las 15:00.
        gaps(token, day)
                .andExpect(jsonPath("$.free.length()").value(2))
                .andExpect(jsonPath("$.free[0].end").value(at(day, "15:00:00")))
                .andExpect(jsonPath("$.free[1].start").value(at(day, "16:00:00")));
    }

    @Test
    @DisplayName("un archivo roto no borra el horario que ya tenia el usuario")
    void brokenFileKeepsExistingSchedule() throws Exception {
        importIcs(token, calendar(vevent("a",
                "DTSTART;" + TZ + ":" + d(day) + "T100000",
                "DTEND;" + TZ + ":" + d(day) + "T120000")))
                .andExpect(status().isOk());

        importIcs(token, "no soy un calendario").andExpect(status().isBadRequest());

        gaps(token, day).andExpect(jsonPath("$.free.length()").value(2));
    }

    @Test
    @DisplayName("el horario importado es de cada usuario")
    void importIsPerUser() throws Exception {
        importIcs(token, calendar(vevent("a",
                "DTSTART;" + TZ + ":" + d(day) + "T100000",
                "DTEND;" + TZ + ":" + d(day) + "T120000")))
                .andExpect(status().isOk());

        gaps(otherToken, day).andExpect(jsonPath("$.free.length()").value(1));
    }
}