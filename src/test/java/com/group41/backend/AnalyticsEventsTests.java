package com.group41.backend;

import com.group41.backend.activity.domain.Activity;
import com.group41.backend.activity.domain.Category;
import com.group41.backend.activity.repository.ActivityRepository;
import com.group41.backend.analytics.AnalyticsPublisher;
import com.group41.backend.schedule.domain.ScheduleSource;
import com.group41.backend.schedule.domain.TimeBlock;
import com.group41.backend.schedule.repository.TimeBlockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Analytics - eventos que emite el backend")
class AnalyticsEventsTests {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final double KM_PER_DEGREE = 111.195;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private TimeBlockRepository timeBlockRepository;

    // Se reemplaza el publicador real: aqui se verifica QUE se emite, no el envio por red.
    @MockitoBean
    private AnalyticsPublisher analytics;

    private static int counter = 0;
    private Account alice;
    private double baseLat;

    private record Account(String token, UUID id) {
    }

    @BeforeEach
    void setUp() throws Exception {
        alice = register();
        // Zona propia (40 grados en adelante), distinta a la de ActivityFeatureTests.
        baseLat = 40.0 + counter * 0.4;
    }

    private Account register() throws Exception {
        String email = "analytics" + (++counter) + "@test.com";

        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"micontrasena123","name":"Test User"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Account(
                body.get("accessToken").asString(),
                UUID.fromString(body.get("user").get("id").asString()));
    }

    /** Guarda una actividad futura a `km` kilometros al norte del punto base. */
    private Activity save(String title, Category category, double km) {
        ZonedDateTime start = ZonedDateTime.now(BOGOTA).plusDays(2).withNano(0);
        return activityRepository.save(new Activity(
                title, null, category, start, start.plusHours(2),
                "Lugar", baseLat + km / KM_PER_DEGREE, -74.0, null));
    }

    private ResultActions recommendations(String tz) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/activities/recommendations")
                .param("lat", String.valueOf(baseLat))
                .param("lon", "-74.0")
                .param("radius", "1.0")
                .header("Authorization", "Bearer " + alice.token());
        if (tz != null) {
            request.param("tz", tz);
        }
        return mockMvc.perform(request);
    }

    private ResultActions join(Object activityId, String recommendationId, String freeTimeMinutes) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/activities/" + activityId + "/join")
                .header("Authorization", "Bearer " + alice.token());
        if (recommendationId != null) {
            request.param("recommendationId", recommendationId);
        }
        if (freeTimeMinutes != null) {
            request.param("freeTimeMinutes", freeTimeMinutes);
        }
        return mockMvc.perform(request);
    }

    private void blockBetween(ZonedDateTime start, ZonedDateTime end) {
        timeBlockRepository.save(new TimeBlock(
                alice.id(), "Clase", start, end, ScheduleSource.MANUAL, null));
    }

    // ------------------------------------------------------------ recomendaciones

    @Test
    @DisplayName("recomendaciones: devuelve los headers y emite recommendation_shown con ese mismo id")
    void recommendationsReturnHeadersAndEmitEvent() throws Exception {
        save("Cine", Category.CULTURA, 0.1);

        MvcResult result = recommendations(null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn();

        String recommendationId = result.getResponse().getHeader("X-Recommendation-Id");
        int freeMinutes = Integer.parseInt(result.getResponse().getHeader("X-Free-Time-Minutes"));

        assertThat(UUID.fromString(recommendationId)).isNotNull(); // lanza si no es un UUID
        assertThat(freeMinutes).isBetween(0, 960); // la ventana 06:00-22:00 dura 960 minutos

        verify(analytics).recommendationShown(alice.id(), recommendationId, freeMinutes);
    }

    @Test
    @DisplayName("una lista vacia devuelve headers pero no emite nada")
    void emptyListEmitsNothing() throws Exception {
        recommendations(null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0))
                .andExpect(header().exists("X-Recommendation-Id"))
                .andExpect(header().exists("X-Free-Time-Minutes"));

        verify(analytics, never()).recommendationShown(any(), any(), anyInt());
    }

    @Test
    @DisplayName("ocupado ahora mismo: freeTimeMinutes es 0")
    void busyUserHasZeroFreeMinutes() throws Exception {
        save("Cine", Category.CULTURA, 0.1);
        ZonedDateTime now = ZonedDateTime.now(BOGOTA);
        blockBetween(now.minusHours(1), now.plusHours(1));

        recommendations(null)
                .andExpect(status().isOk())
                .andExpect(header().string("X-Free-Time-Minutes", "0"));

        verify(analytics).recommendationShown(any(), any(), org.mockito.ArgumentMatchers.eq(0));
    }

    @Test
    @DisplayName("un bloque que empieza en 30 minutos limita el tiempo libre a 30")
    void upcomingBlockCapsFreeMinutes() throws Exception {
        save("Cine", Category.CULTURA, 0.1);
        ZonedDateTime now = ZonedDateTime.now(BOGOTA);
        blockBetween(now.plusMinutes(30), now.plusMinutes(90));

        MvcResult result = recommendations(null).andExpect(status().isOk()).andReturn();

        // Dentro de la ventana diaria son ~29 minutos; fuera de ella, 0. Nunca mas de 30.
        int freeMinutes = Integer.parseInt(result.getResponse().getHeader("X-Free-Time-Minutes"));
        assertThat(freeMinutes).isBetween(0, 30);
    }

    @Test
    @DisplayName("una zona horaria invalida da 400 y no emite nada")
    void invalidTimezoneReturnsBadRequest() throws Exception {
        save("Cine", Category.CULTURA, 0.1);

        recommendations("Marte/Olimpo").andExpect(status().isBadRequest());

        verifyNoInteractions(analytics);
    }

    // ------------------------------------------------------------------ join

    @Test
    @DisplayName("unirse desde una recomendacion emite recommended_activity_selected")
    void joinFromRecommendationEmitsSelection() throws Exception {
        Activity activity = save("Futbol", Category.DEPORTES, 0.1);
        String recommendationId = UUID.randomUUID().toString();

        join(activity.getId(), recommendationId, "45").andExpect(status().isOk());

        verify(analytics).activitySelected(
                alice.id(), activity.getId().toString(), "DEPORTES", recommendationId, 45);
    }

    @Test
    @DisplayName("unirse sin recommendationId (busqueda normal) no emite nada")
    void joinWithoutRecommendationEmitsNothing() throws Exception {
        Activity activity = save("Futbol", Category.DEPORTES, 0.1);

        join(activity.getId(), null, null).andExpect(status().isOk());

        verifyNoInteractions(analytics);
    }

    @Test
    @DisplayName("datos de analitica mal formados nunca impiden unirse")
    void badAnalyticsParamsNeverBlockJoin() throws Exception {
        String recommendationId = UUID.randomUUID().toString();

        // recommendationId que no es UUID: se une, pero no se emite.
        Activity first = save("Uno", Category.DEPORTES, 0.1);
        join(first.getId(), "no-es-un-uuid", "45").andExpect(status().isOk());
        verifyNoInteractions(analytics);

        // freeTimeMinutes que no es un numero: se emite sin los minutos.
        Activity second = save("Dos", Category.ESTUDIO, 0.2);
        join(second.getId(), recommendationId, "abc").andExpect(status().isOk());
        verify(analytics).activitySelected(
                alice.id(), second.getId().toString(), "ESTUDIO", recommendationId, null);

        // freeTimeMinutes negativo: tambien se descarta.
        Activity third = save("Tres", Category.CULTURA, 0.3);
        join(third.getId(), recommendationId, "-5").andExpect(status().isOk());
        verify(analytics).activitySelected(
                alice.id(), third.getId().toString(), "CULTURA", recommendationId, null);
    }

    @Test
    @DisplayName("un join que falla (404 o 409) no emite, y un repetido no cuenta dos veces")
    void failedJoinEmitsNothing() throws Exception {
        String recommendationId = UUID.randomUUID().toString();

        join(UUID.randomUUID(), recommendationId, "45").andExpect(status().isNotFound());
        verifyNoInteractions(analytics);

        Activity activity = save("Yoga", Category.DEPORTES, 0.1);
        join(activity.getId(), recommendationId, "45").andExpect(status().isOk());
        join(activity.getId(), recommendationId, "45").andExpect(status().isConflict());

        verify(analytics, times(1)).activitySelected(any(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------- friends/gaps

    @Test
    @DisplayName("consultar amigos disponibles emite friend_availability_used con action=viewed")
    void friendsGapsEmitsViewed() throws Exception {
        mockMvc.perform(get("/api/friends/gaps")
                        .param("dateTime", "2026-10-02T15:00:00Z")
                        .header("Authorization", "Bearer " + alice.token()))
                .andExpect(status().isOk());

        verify(analytics).friendAvailabilityUsed(alice.id(), "viewed");
    }

    @Test
    @DisplayName("una consulta de amigos que falla (400 o 401) no emite nada")
    void failedFriendsGapsEmitsNothing() throws Exception {
        mockMvc.perform(get("/api/friends/gaps")
                        .header("Authorization", "Bearer " + alice.token()))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/friends/gaps").param("dateTime", "2026-10-02T15:00:00Z"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(analytics);
    }
}