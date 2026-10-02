package com.group41.backend;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Horarios - huecos libres y amigos disponibles")
class ScheduleFeatureTests {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TimeBlockRepository timeBlockRepository;

    private static int counter = 0;
    private Account alice;
    private Account bob;

    private record Account(String email, String token, UUID id) {
    }

    @BeforeEach
    void setUp() throws Exception {
        alice = register();
        bob = register();
    }

    private Account register() throws Exception {
        String email = "schedule" + (++counter) + "@test.com";

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

    /** Bloque del 2 de octubre de 2026, de fromHour a toHour, hora de Bogota. */
    private void block(Account who, int fromHour, int toHour) {
        timeBlockRepository.save(new TimeBlock(
                who.id(),
                "Clase",
                ZonedDateTime.of(2026, 10, 2, fromHour, 0, 0, 0, BOGOTA),
                ZonedDateTime.of(2026, 10, 2, toHour, 0, 0, 0, BOGOTA),
                ScheduleSource.MANUAL,
                null));
    }

    private ResultActions gaps(Account who, String date) throws Exception {
        return mockMvc.perform(get("/api/schedules/me/gaps")
                .param("date", date)
                .header("Authorization", "Bearer " + who.token()));
    }

    private void makeFriends(Account a, Account b) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/friends/requests")
                        .header("Authorization", "Bearer " + a.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"addresseeEmail":"%s"}
                                """.formatted(b.email())))
                .andExpect(status().isCreated())
                .andReturn();

        String requestId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asString();

        mockMvc.perform(patch("/api/friends/requests/" + requestId)
                        .header("Authorization", "Bearer " + b.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accept\":true}"))
                .andExpect(status().isOk());
    }

    private List<String> availableFriendEmails(Account who, String dateTime) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/friends/gaps")
                        .param("dateTime", dateTime)
                        .header("Authorization", "Bearer " + who.token()))
                .andExpect(status().isOk())
                .andReturn();

        List<String> emails = new ArrayList<>();
        objectMapper.readTree(result.getResponse().getContentAsString())
                .forEach(node -> emails.add(node.get("email").asString()));
        return emails;
    }

    // ---------------------------------------------------------------- /me/gaps

    @Test
    @DisplayName("los huecos exigen token")
    void gapsRequireAuth() throws Exception {
        mockMvc.perform(get("/api/schedules/me/gaps").param("date", "2026-10-02"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("sin bloques, el dia completo (06:00-22:00 Bogota) esta libre")
    void wholeDayIsFreeWithoutBlocks() throws Exception {
        gaps(alice, "2026-10-02")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-10-02"))
                .andExpect(jsonPath("$.timezone").value("America/Bogota"))
                .andExpect(jsonPath("$.free.length()").value(1))
                .andExpect(jsonPath("$.free[0].start").value("2026-10-02T06:00:00-05:00"))
                .andExpect(jsonPath("$.free[0].end").value("2026-10-02T22:00:00-05:00"));
    }

    @Test
    @DisplayName("los bloques parten el dia en intervalos libres")
    void blocksSplitTheDay() throws Exception {
        block(alice, 10, 12);

        gaps(alice, "2026-10-02")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.free.length()").value(2))
                .andExpect(jsonPath("$.free[0].start").value("2026-10-02T06:00:00-05:00"))
                .andExpect(jsonPath("$.free[0].end").value("2026-10-02T10:00:00-05:00"))
                .andExpect(jsonPath("$.free[1].start").value("2026-10-02T12:00:00-05:00"))
                .andExpect(jsonPath("$.free[1].end").value("2026-10-02T22:00:00-05:00"));
    }

    @Test
    @DisplayName("un bloque que se sale de la ventana se recorta")
    void blocksOutsideTheWindowAreClipped() throws Exception {
        block(alice, 5, 8);
        block(alice, 21, 23);

        gaps(alice, "2026-10-02")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.free.length()").value(1))
                .andExpect(jsonPath("$.free[0].start").value("2026-10-02T08:00:00-05:00"))
                .andExpect(jsonPath("$.free[0].end").value("2026-10-02T21:00:00-05:00"));
    }

    @Test
    @DisplayName("los bloques de otro usuario no afectan mis huecos")
    void otherUsersBlocksAreIgnored() throws Exception {
        block(bob, 8, 20);

        gaps(alice, "2026-10-02")
                .andExpect(jsonPath("$.free.length()").value(1))
                .andExpect(jsonPath("$.free[0].start").value("2026-10-02T06:00:00-05:00"));
    }

    @Test
    @DisplayName("?tz= cambia que significa 'el dia' y el formato de las horas")
    void timezoneParamShiftsTheWindow() throws Exception {
        // 10:00-12:00 Bogota = 15:00-17:00 UTC
        block(alice, 10, 12);

        mockMvc.perform(get("/api/schedules/me/gaps")
                        .param("date", "2026-10-02")
                        .param("tz", "UTC")
                        .header("Authorization", "Bearer " + alice.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("UTC"))
                .andExpect(jsonPath("$.free.length()").value(2))
                .andExpect(jsonPath("$.free[0].start").value("2026-10-02T06:00:00Z"))
                .andExpect(jsonPath("$.free[0].end").value("2026-10-02T15:00:00Z"))
                .andExpect(jsonPath("$.free[1].start").value("2026-10-02T17:00:00Z"))
                .andExpect(jsonPath("$.free[1].end").value("2026-10-02T22:00:00Z"));
    }

    @Test
    @DisplayName("zona invalida, fecha invalida o fecha ausente dan 400")
    void invalidInputReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/schedules/me/gaps")
                        .param("date", "2026-10-02")
                        .param("tz", "Marte/Olimpo")
                        .header("Authorization", "Bearer " + alice.token()))
                .andExpect(status().isBadRequest());

        gaps(alice, "no-es-una-fecha").andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/schedules/me/gaps")
                        .header("Authorization", "Bearer " + alice.token()))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------- /friends/gaps

    @Test
    @DisplayName("solo aparecen los amigos que estan libres en ese instante")
    void friendsGapsFiltersBusyFriends() throws Exception {
        Account carol = register();
        Account dave = register(); // no es amigo de nadie
        makeFriends(alice, bob);
        makeFriends(alice, carol);

        // Bob ocupado 10:00-12:00 Bogota (15:00-17:00 UTC). Carol y Dave sin bloques.
        block(bob, 10, 12);

        // 16:00 UTC: Bob esta ocupado. Solo Carol; Dave no es amigo.
        assertThat(availableFriendEmails(alice, "2026-10-02T16:00:00Z"))
                .containsExactly(carol.email());

        // 18:00 UTC: Bob ya termino.
        assertThat(availableFriendEmails(alice, "2026-10-02T18:00:00Z"))
                .containsExactlyInAnyOrder(bob.email(), carol.email())
                .doesNotContain(dave.email());
    }

    @Test
    @DisplayName("el bloque incluye su inicio y excluye su fin")
    void friendsGapsBoundaries() throws Exception {
        makeFriends(alice, bob);
        block(bob, 10, 12); // 15:00-17:00 UTC

        assertThat(availableFriendEmails(alice, "2026-10-02T14:59:59Z")).containsExactly(bob.email());
        assertThat(availableFriendEmails(alice, "2026-10-02T15:00:00Z")).isEmpty();
        assertThat(availableFriendEmails(alice, "2026-10-02T16:59:59Z")).isEmpty();
        assertThat(availableFriendEmails(alice, "2026-10-02T17:00:00Z")).containsExactly(bob.email());
    }

    @Test
    @DisplayName("sin amigos devuelve lista vacia; sin token da 401; sin dateTime da 400")
    void friendsGapsEdgeCases() throws Exception {
        assertThat(availableFriendEmails(alice, "2026-10-02T15:00:00Z")).isEmpty();

        mockMvc.perform(get("/api/friends/gaps").param("dateTime", "2026-10-02T15:00:00Z"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/friends/gaps")
                        .header("Authorization", "Bearer " + alice.token()))
                .andExpect(status().isBadRequest());
    }
}