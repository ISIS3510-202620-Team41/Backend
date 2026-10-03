package com.group41.backend;

import com.group41.backend.common.ApiException;
import com.group41.backend.schedule.service.GoogleCalendarApi.RawCalendar;
import com.group41.backend.schedule.domain.ScheduleSource;
import com.group41.backend.schedule.domain.TimeBlock;
import com.group41.backend.schedule.repository.GoogleCredentialRepository;
import com.group41.backend.schedule.repository.TimeBlockRepository;
import com.group41.backend.schedule.service.GoogleAuthException;
import com.group41.backend.schedule.service.GoogleCalendarApi;
import com.group41.backend.schedule.service.GoogleCalendarApi.RawEvent;
import com.group41.backend.schedule.service.GoogleCalendarApi.Tokens;
import com.group41.backend.schedule.service.TokenCrypto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import static org.mockito.Mockito.never;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Horarios - sincronizacion con Google Calendar")
class GoogleCalendarTests {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    @MockitoBean
    private GoogleCalendarApi googleApi;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GoogleCredentialRepository credentialRepository;

    @Autowired
    private TimeBlockRepository timeBlockRepository;

    @Autowired
    private TokenCrypto crypto;

    private static int counter = 0;
    private String token;
    private UUID userId;
    private LocalDate day;

    @BeforeEach
    void setUp() throws Exception {
        String email = "google" + (++counter) + "@test.com";
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"micontrasena123","name":"Test User"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        token = body.get("accessToken").asString();
        userId = UUID.fromString(body.get("user").get("id").asString());
        day = LocalDate.now(BOGOTA).plusDays(3);
    }

    // ------------------------------------------------------------------ helpers

    private String dt(LocalDate date, int hour) {
        return "%sT%02d:00:00-05:00".formatted(date, hour);
    }

    private RawEvent timed(String id, String title, LocalDate date, int from, int to) {
        return new RawEvent(id, title, "confirmed", null, "default", false,
                dt(date, from), null, dt(date, to), null, "America/Bogota");
    }

    private RawEvent allDay(String id, LocalDate date) {
        return new RawEvent(id, "Festivo", "confirmed", null, "default", false,
                null, date.toString(), null, date.plusDays(1).toString(), null);
    }

    private static RawCalendar primaryCalendar() {
        return new RawCalendar("primary", "Principal", true, true);
    }

    private void stubConnect(String code, String access, String refresh, List<RawEvent> events) {
        when(googleApi.exchangeAuthCode(code)).thenReturn(new Tokens(access, refresh));
        when(googleApi.listCalendars(access)).thenReturn(List.of(primaryCalendar()));
        when(googleApi.listEvents(eq(access), eq("primary"), any(Instant.class), any(Instant.class)))
                .thenReturn(events);
    }

    private ResultActions connect(String code) throws Exception {
        return mockMvc.perform(post("/api/schedules/sync/google")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"authCode\":\"" + code + "\"}"));
    }

    private ResultActions resync() throws Exception {
        return mockMvc.perform(post("/api/schedules/sync/google/refresh")
                .header("Authorization", "Bearer " + token));
    }

    private ResultActions gaps(LocalDate date) throws Exception {
        return mockMvc.perform(get("/api/schedules/me/gaps")
                .param("date", date.toString())
                .header("Authorization", "Bearer " + token));
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("los dos endpoints de Google exigen token")
    void requireAuth() throws Exception {
        mockMvc.perform(get("/api/schedules/google/status"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/schedules/sync/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authCode\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/schedules/sync/google/refresh"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("el estado de conexion no contacta Google")
    void connectionStatusUsesStoredCredential() throws Exception {
        mockMvc.perform(get("/api/schedules/google/status")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(false));

        credentialRepository.save(new com.group41.backend.schedule.domain.GoogleCredential(
                userId, crypto.encrypt("refresh-token")));

        mockMvc.perform(get("/api/schedules/google/status")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true));

        verify(googleApi, never()).refreshAccessToken(any());
        verify(googleApi, never()).listCalendars(any());
    }

    @Test
    @DisplayName("sin authCode da 400")
    void authCodeIsRequired() throws Exception {
        mockMvc.perform(post("/api/schedules/sync/google")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.authCode").exists());
    }

    @Test
    @DisplayName("importa eventos con hora y de dia completo, e ignora cancelados, libres, rechazados y rotos")
    void importsOnlyEventsThatTakeTime() throws Exception {
        List<RawEvent> events = List.of(
                timed("e1", "Calculo", day, 10, 12),
                new RawEvent("e2", "Cancelada", "cancelled", null, "default", false,
                        dt(day, 14), null, dt(day, 15), null, null),
                new RawEvent("e3", "Libre", "confirmed", "transparent", "default", false,
                        dt(day, 15), null, dt(day, 16), null, null),
                new RawEvent("e4", "Rechazada", "confirmed", null, "default", true,
                        dt(day, 16), null, dt(day, 17), null, null),
                new RawEvent("e5", "Oficina", "confirmed", null, "workingLocation", false,
                        dt(day, 17), null, dt(day, 18), null, null),
                allDay("e6", day.plusDays(1)),
                new RawEvent("e7", "Rota", "confirmed", null, "default", false,
                        "no-es-fecha", null, "tampoco", null, null));
        stubConnect("code-1", "access-1", "refresh-1", events);

        connect("code-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(2))
                .andExpect(jsonPath("$.skippedEvents").value(1));

        gaps(day)
                .andExpect(jsonPath("$.free.length()").value(2))
                .andExpect(jsonPath("$.free[0].end").value(dt(day, 10)))
                .andExpect(jsonPath("$.free[1].start").value(dt(day, 12)));
        gaps(day.plusDays(1)).andExpect(jsonPath("$.free.length()").value(0));
    }

    @Test
    @DisplayName("el refresh token se guarda cifrado")
    void refreshTokenIsStoredEncrypted() throws Exception {
        stubConnect("code-1", "access-1", "refresh-secreto", List.of());

        connect("code-1").andExpect(status().isOk());

        String stored = credentialRepository.findByUserId(userId).orElseThrow().getEncryptedRefreshToken();
        assertThat(stored).doesNotContain("refresh-secreto");
        assertThat(crypto.decrypt(stored)).isEqualTo("refresh-secreto");
    }

    @Test
    @DisplayName("resincronizar usa el token guardado y reemplaza los bloques de Google")
    void resyncUsesStoredTokenAndReplacesBlocks() throws Exception {
        stubConnect("code-1", "access-1", "refresh-1", List.of(timed("e1", "Calculo", day, 10, 12)));
        connect("code-1").andExpect(status().isOk());

        when(googleApi.refreshAccessToken("refresh-1")).thenReturn("access-2");
        when(googleApi.listCalendars("access-2")).thenReturn(List.of(primaryCalendar()));
        when(googleApi.listEvents(eq("access-2"), eq("primary"), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(timed("e2", "Fisica", day, 15, 16)));

        resync()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1));

        // El bloque de las 10:00 desaparecio; solo queda el de las 15:00.
        gaps(day)
                .andExpect(jsonPath("$.free.length()").value(2))
                .andExpect(jsonPath("$.free[0].end").value(dt(day, 15)))
                .andExpect(jsonPath("$.free[1].start").value(dt(day, 16)));
    }

    @Test
    @DisplayName("resincronizar sin haber conectado antes da 409")
    void resyncWithoutConnectionIsConflict() throws Exception {
        resync().andExpect(status().isConflict());
    }

    @Test
    @DisplayName("si Google revoco el acceso: 409 y se borra el token guardado")
    void revokedAccessDeletesStoredToken() throws Exception {
        stubConnect("code-1", "access-1", "refresh-1", List.of());
        connect("code-1").andExpect(status().isOk());

        when(googleApi.refreshAccessToken(any())).thenThrow(new GoogleAuthException("revocado"));

        resync().andExpect(status().isConflict());
        assertThat(credentialRepository.existsByUserId(userId)).isFalse();

        // Sin token guardado ya ni siquiera llega a Google.
        resync().andExpect(status().isConflict());
        verify(googleApi, times(1)).refreshAccessToken(any());
    }

    @Test
    @DisplayName("un codigo invalido o expirado da 400")
    void invalidAuthCodeIsBadRequest() throws Exception {
        when(googleApi.exchangeAuthCode("malo")).thenThrow(new GoogleAuthException("invalido"));

        connect("malo").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("sin refresh token: 400 si nunca habia conectado; si ya habia, conserva el guardado")
    void missingRefreshTokenHandling() throws Exception {
        stubConnect("code-1", "access-1", null, List.of());
        connect("code-1").andExpect(status().isBadRequest());

        stubConnect("code-2", "access-2", "refresh-2", List.of());
        connect("code-2").andExpect(status().isOk());

        // Google ya no vuelve a entregar refresh token; el guardado sigue sirviendo.
        stubConnect("code-3", "access-3", null, List.of());
        connect("code-3").andExpect(status().isOk());
        assertThat(crypto.decrypt(credentialRepository.findByUserId(userId).orElseThrow()
                .getEncryptedRefreshToken())).isEqualTo("refresh-2");
    }

    @Test
    @DisplayName("sincronizar Google no toca los bloques de otros origenes")
    void googleSyncKeepsOtherSources() throws Exception {
        timeBlockRepository.save(new TimeBlock(
                userId, "Clase del .ics",
                ZonedDateTime.of(day, LocalTime.of(14, 0), BOGOTA),
                ZonedDateTime.of(day, LocalTime.of(16, 0), BOGOTA),
                ScheduleSource.ICS, null));
        stubConnect("code-1", "access-1", "refresh-1", List.of(timed("e1", "Calculo", day, 10, 12)));

        connect("code-1").andExpect(status().isOk());

        // Quedan libres 06-10, 12-14 y 16-22: el bloque ICS sigue ahi.
        gaps(day).andExpect(jsonPath("$.free.length()").value(3));
    }

        @Test
    @DisplayName("lee todos los calendarios visibles, no los ocultos ni los de festivos")
    void readsAllVisibleCalendars() throws Exception {
        when(googleApi.exchangeAuthCode("code-1")).thenReturn(new Tokens("access-1", "refresh-1"));
        when(googleApi.listCalendars("access-1")).thenReturn(List.of(
                primaryCalendar(),
                new RawCalendar("personal-id", "Personal", false, true),
                new RawCalendar("oculto-id", "Oculto", false, false),
                new RawCalendar("es.co#holiday@group.v.calendar.google.com", "Festivos", false, true)));
        when(googleApi.listEvents(eq("access-1"), eq("primary"), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(timed("e1", "Calculo", day, 10, 12)));
        when(googleApi.listEvents(eq("access-1"), eq("personal-id"), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(timed("e2", "Gimnasio", day, 15, 16), timed("e3", "Cena", day, 19, 21)));

        connect("code-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(3))
                .andExpect(jsonPath("$.calendars").value(2));

        verify(googleApi, never()).listEvents(eq("access-1"), eq("oculto-id"),
                any(Instant.class), any(Instant.class));
        verify(googleApi, never()).listEvents(eq("access-1"), eq("es.co#holiday@group.v.calendar.google.com"),
                any(Instant.class), any(Instant.class));

        // Bloques 10-12, 15-16 y 19-21: quedan 4 huecos.
        gaps(day).andExpect(jsonPath("$.free.length()").value(4));
    }

    @Test
    @DisplayName("sin permiso para listar calendarios da 409 en vez de importar a medias")
    void missingCalendarListScopeIsConflict() throws Exception {
        when(googleApi.exchangeAuthCode("code-1")).thenReturn(new Tokens("access-1", "refresh-1"));
        when(googleApi.listCalendars("access-1"))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "Falta el permiso"));

        connect("code-1").andExpect(status().isConflict());
    }
}