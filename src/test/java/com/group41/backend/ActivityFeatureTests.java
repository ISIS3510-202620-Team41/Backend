package com.group41.backend;

import com.group41.backend.activity.domain.Activity;
import com.group41.backend.activity.domain.Category;
import com.group41.backend.activity.repository.ActivityRepository;
import com.group41.backend.user.repository.UserPreferenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Actividades - busqueda, inscripcion y recomendaciones")
class ActivityFeatureTests {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final double KM_PER_DEGREE = 111.195;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private UserPreferenceRepository preferenceRepository;

    private static int counter = 0;
    private Account alice;
    private Account bob;
    private double baseLat;

    private record Account(String email, String token, UUID id) {
    }

    @BeforeEach
    void setUp() throws Exception {
        alice = register();
        bob = register();
        // Zona propia para este test: los datos se comparten entre tests.
        baseLat = 10.0 + counter * 0.4;
    }

    private Account register() throws Exception {
        String email = "activity" + (++counter) + "@test.com";

        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"micontrasena123","name":"Test User"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Account(
                email,
                body.get("accessToken").asString(),
                UUID.fromString(body.get("user").get("id").asString()));
    }

    private double latAt(double km) {
        return baseLat + km / KM_PER_DEGREE;
    }

    private static ZonedDateTime inDays(int days) {
        return ZonedDateTime.now(BOGOTA).plusDays(days).withNano(0);
    }

    private static String iso(ZonedDateTime dateTime) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(dateTime);
    }

    /** Guarda una actividad futura a `km` kilometros al norte del punto base. */
    private Activity save(String title, Category category, double km) {
        return saveBetween(title, category, km, inDays(2), inDays(2).plusHours(2));
    }

    private Activity saveBetween(String title, Category category, double km,
                                 ZonedDateTime start, ZonedDateTime end) {
        return activityRepository.save(new Activity(
                title, null, category, start, end, "Lugar", latAt(km), -74.0, null));
    }

    private String createBody(String title, ZonedDateTime start, ZonedDateTime end, double latitude) {
        return """
                {"title":"%s","description":"Partido amistoso","category":"DEPORTES","startTime":"%s","endTime":"%s","locationName":"Cancha","latitude":%s,"longitude":-74.0}
                """.formatted(title, iso(start), iso(end), latitude);
    }

    private ResultActions create(Account who, String body) throws Exception {
        return mockMvc.perform(post("/api/activities")
                .header("Authorization", "Bearer " + who.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions nearby(Account who, double radiusKm) throws Exception {
        return mockMvc.perform(get("/api/activities")
                .param("lat", String.valueOf(baseLat))
                .param("lon", "-74.0")
                .param("radius", String.valueOf(radiusKm))
                .header("Authorization", "Bearer " + who.token()));
    }

    private ResultActions recommendations(Account who, double radiusKm) throws Exception {
        return mockMvc.perform(get("/api/activities/recommendations")
                .param("lat", String.valueOf(baseLat))
                .param("lon", "-74.0")
                .param("radius", String.valueOf(radiusKm))
                .header("Authorization", "Bearer " + who.token()));
    }

    private ResultActions join(Account who, Object activityId) throws Exception {
        return mockMvc.perform(post("/api/activities/" + activityId + "/join")
                .header("Authorization", "Bearer " + who.token()));
    }

    private ResultActions leave(Account who, Object activityId) throws Exception {
        return mockMvc.perform(delete("/api/activities/" + activityId + "/leave")
                .header("Authorization", "Bearer " + who.token()));
    }

    // ------------------------------------------------------------------ acceso

    @Test
    @DisplayName("todos los endpoints de actividades exigen token")
    void endpointsRequireAuth() throws Exception {
        mockMvc.perform(get("/api/activities").param("lat", "4.6").param("lon", "-74.0"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/activities/recommendations").param("lat", "4.6").param("lon", "-74.0"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/activities/" + UUID.randomUUID() + "/join"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/activities/" + UUID.randomUUID() + "/leave"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ crear

    @Test
    @DisplayName("crear una actividad responde 201 y luego aparece en la busqueda")
    void createdActivityShowsUpInSearch() throws Exception {
        create(alice, createBody("Futbol", inDays(2), inDays(2).plusHours(2), baseLat))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.title").value("Futbol"))
                .andExpect(jsonPath("$.category").value("DEPORTES"))
                .andExpect(jsonPath("$.participants").value(0))
                .andExpect(jsonPath("$.joined").value(false));

        nearby(bob, 1.0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Futbol"));
    }

    @Test
    @DisplayName("crear valida titulo, fechas y coordenadas")
    void createValidatesInput() throws Exception {
        create(alice, createBody("", inDays(2), inDays(2).plusHours(2), baseLat))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.title").exists());

        create(alice, createBody("Mal", inDays(2), inDays(2).minusHours(1), baseLat))
                .andExpect(status().isBadRequest());

        create(alice, createBody("Pasada", inDays(-3), inDays(-3).plusHours(2), baseLat))
                .andExpect(status().isBadRequest());

        create(alice, createBody("Fuera", inDays(2), inDays(2).plusHours(2), 95.0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.latitude").exists());
    }

    // ------------------------------------------------------------------ buscar

    @Test
    @DisplayName("la busqueda valida coordenadas y radio")
    void searchValidatesParameters() throws Exception {
        String auth = "Bearer " + alice.token();

        mockMvc.perform(get("/api/activities").param("lat", "95").param("lon", "-74")
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/activities").param("lat", "4.6").param("lon", "-74").param("radius", "0")
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/activities").param("lat", "4.6").param("lon", "-74").param("radius", "500")
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/activities").header("Authorization", auth))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/activities").param("lat", "abc").param("lon", "-74")
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("el radio filtra y el resultado va de la mas cercana a la mas lejana")
    void radiusFiltersAndResultsAreSortedByDistance() throws Exception {
        save("Media", Category.DEPORTES, 0.5);
        save("Lejos", Category.ESTUDIO, 5.0);
        save("Cerca", Category.CULTURA, 0.1);

        nearby(alice, 1.0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("Cerca"))
                .andExpect(jsonPath("$[0].distanceKm").value(0.1))
                .andExpect(jsonPath("$[1].title").value("Media"))
                .andExpect(jsonPath("$[1].distanceKm").value(0.5));

        nearby(alice, 10.0)
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[2].title").value("Lejos"));
    }

    @Test
    @DisplayName("las actividades que ya terminaron no aparecen")
    void endedActivitiesAreHidden() throws Exception {
        saveBetween("Pasada", Category.CULTURA, 0.1, inDays(-1), inDays(-1).plusHours(2));
        save("Futura", Category.CULTURA, 0.1);

        nearby(alice, 1.0)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Futura"));
    }

    // ------------------------------------------------------------------ unirse y salir

    @Test
    @DisplayName("unirse: 200, repetir da 409, id desconocido 404, terminada 409, id invalido 400")
    void joinRules() throws Exception {
        Activity activity = save("Yoga", Category.DEPORTES, 0.1);

        join(alice, activity.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participants").value(1))
                .andExpect(jsonPath("$.joined").value(true));

        join(alice, activity.getId()).andExpect(status().isConflict());
        join(alice, UUID.randomUUID()).andExpect(status().isNotFound());

        Activity ended = saveBetween("Pasada", Category.CULTURA, 0.1, inDays(-1), inDays(-1).plusHours(2));
        join(alice, ended.getId()).andExpect(status().isConflict());

        join(alice, "no-es-un-uuid").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("salir: 204, y salir de nuevo da 404")
    void leaveRules() throws Exception {
        Activity activity = save("Yoga", Category.DEPORTES, 0.1);
        join(alice, activity.getId()).andExpect(status().isOk());

        leave(alice, activity.getId()).andExpect(status().isNoContent());

        nearby(alice, 1.0)
                .andExpect(jsonPath("$[0].participants").value(0))
                .andExpect(jsonPath("$[0].joined").value(false));

        leave(alice, activity.getId()).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("los inscritos se cuentan para todos y 'joined' es de cada usuario")
    void participantsAreSharedButJoinedIsPersonal() throws Exception {
        Activity activity = save("Ajedrez", Category.ENTRETENIMIENTO, 0.1);
        join(alice, activity.getId()).andExpect(status().isOk());

        nearby(bob, 1.0)
                .andExpect(jsonPath("$[0].participants").value(1))
                .andExpect(jsonPath("$[0].joined").value(false));

        nearby(alice, 1.0)
                .andExpect(jsonPath("$[0].participants").value(1))
                .andExpect(jsonPath("$[0].joined").value(true));
    }

    // ------------------------------------------------------------------ preferencias

    @Test
    @DisplayName("unirse sube el puntaje de la categoria y salir lo deja en cero")
    void preferenceScoreFollowsJoinAndLeave() throws Exception {
        Activity activity = save("Futbol", Category.DEPORTES, 0.1);

        join(alice, activity.getId()).andExpect(status().isOk());
        assertThat(preferenceRepository.findByUserIdAndCategory(alice.id(), Category.DEPORTES)
                .orElseThrow().getScore())
                .isCloseTo(1.0, within(0.0001)); // d=0 -> 1/(1+0)

        leave(alice, activity.getId()).andExpect(status().isNoContent());
        assertThat(preferenceRepository.findByUserIdAndCategory(alice.id(), Category.DEPORTES)
                .orElseThrow().getScore())
                .isCloseTo(0.0, within(0.0001));
    }

    // ------------------------------------------------------------------ recomendaciones

    @Test
    @DisplayName("las recomendaciones combinan preferencias y distancia, y excluyen lo ya inscrito")
    void recommendationsUsePreferencesAndExcludeJoined() throws Exception {
        save("Cine cerca", Category.CULTURA, 0.1);
        Activity yoga = save("Yoga inscrita", Category.DEPORTES, 0.5);
        save("Futbol lejos", Category.DEPORTES, 0.8);

        join(alice, yoga.getId()).andExpect(status().isOk());

        // Alice prefiere DEPORTES: Futbol (0.68) le gana a Cine (0.36) aunque este mas lejos.
        recommendations(alice, 1.0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].activity.title").value("Futbol lejos"))
                .andExpect(jsonPath("$[1].activity.title").value("Cine cerca"));

        // Bob no tiene preferencias: manda la distancia y ve las tres.
        recommendations(bob, 1.0)
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].activity.title").value("Cine cerca"))
                .andExpect(jsonPath("$[1].activity.title").value("Yoga inscrita"))
                .andExpect(jsonPath("$[2].activity.title").value("Futbol lejos"));
    }
}