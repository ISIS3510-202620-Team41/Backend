package com.group41.backend;

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
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Amigos - solicitudes y lista")
class FriendshipFeatureTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static int counter = 0;
    private Account alice;
    private Account bob;

    private record Account(String email, String token) {
    }

    @BeforeEach
    void setUp() throws Exception {
        alice = register();
        bob = register();
    }

    private Account register() throws Exception {
        String email = "friends" + (++counter) + "@test.com";

        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"micontrasena123","name":"Test User"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();

        String token = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asString();
        return new Account(email, token);
    }

    private ResultActions sendRequest(Account from, String toEmail) throws Exception {
        return mockMvc.perform(post("/api/friends/requests")
                .header("Authorization", "Bearer " + from.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"addresseeEmail":"%s"}
                        """.formatted(toEmail)));
    }

    /** Envia la solicitud esperando exito y devuelve el id de la solicitud. */
    private String sendRequestOk(Account from, Account to) throws Exception {
        MvcResult result = sendRequest(from, to.email())
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asString();
    }

    private ResultActions respond(Account who, String requestId, boolean accept) throws Exception {
        return mockMvc.perform(patch("/api/friends/requests/" + requestId)
                .header("Authorization", "Bearer " + who.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"accept":%s}
                        """.formatted(accept)));
    }

    private ResultActions pending(Account who) throws Exception {
        return mockMvc.perform(get("/api/friends/requests/pending")
                .header("Authorization", "Bearer " + who.token()));
    }

    private ResultActions friends(Account who) throws Exception {
        return mockMvc.perform(get("/api/friends")
                .header("Authorization", "Bearer " + who.token()));
    }

    @Test
    @DisplayName("la solicitud aparece como pendiente para el destinatario y no para quien la envio")
    void requestShowsUpInAddresseePending() throws Exception {
        sendRequestOk(alice, bob);

        pending(bob)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].user.email").value(alice.email()));

        pending(alice)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("el correo del destinatario se normaliza a minusculas")
    void addresseeEmailIsNormalized() throws Exception {
        sendRequest(alice, bob.email().toUpperCase())
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("aceptar crea la amistad en ambos sentidos y limpia los pendientes")
    void acceptingCreatesMutualFriendship() throws Exception {
        String requestId = sendRequestOk(alice, bob);

        respond(bob, requestId, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        friends(alice)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].email").value(bob.email()));

        friends(bob)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].email").value(alice.email()));

        pending(bob)
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("rechazar no crea amistad")
    void rejectingDoesNotCreateFriendship() throws Exception {
        String requestId = sendRequestOk(alice, bob);

        respond(bob, requestId, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        friends(alice).andExpect(jsonPath("$.length()").value(0));
        friends(bob).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("quien fue rechazado no puede reenviar, pero quien rechazo si puede escribirle")
    void rejectedCannotInsistButRejectorCanReachOut() throws Exception {
        String requestId = sendRequestOk(alice, bob);
        respond(bob, requestId, false).andExpect(status().isOk());

        sendRequest(alice, bob.email()).andExpect(status().isConflict());

        // Bob cambia de opinion y le escribe a Alice.
        sendRequest(bob, alice.email()).andExpect(status().isCreated());
        pending(alice).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("no se puede enviar una solicitud a uno mismo")
    void cannotRequestYourself() throws Exception {
        sendRequest(alice, alice.email()).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("un correo que no existe da 404")
    void unknownEmailReturnsNotFound() throws Exception {
        sendRequest(alice, "nadie-" + alice.email()).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("una solicitud repetida o cruzada da 409")
    void duplicateRequestsAreRejected() throws Exception {
        sendRequestOk(alice, bob);

        // Repetir la misma.
        sendRequest(alice, bob.email()).andExpect(status().isConflict());
        // Bob le escribe a Alice mientras la de ella sigue pendiente.
        sendRequest(bob, alice.email()).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("quienes ya son amigos no pueden volver a solicitarse")
    void friendsCannotRequestAgain() throws Exception {
        respond(bob, sendRequestOk(alice, bob), true).andExpect(status().isOk());

        sendRequest(alice, bob.email()).andExpect(status().isConflict());
        sendRequest(bob, alice.email()).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("solo el destinatario puede responder: el remitente recibe 404")
    void onlyAddresseeCanRespond() throws Exception {
        String requestId = sendRequestOk(alice, bob);

        respond(alice, requestId, true).andExpect(status().isNotFound());

        // Un tercero tampoco.
        Account carol = register();
        respond(carol, requestId, true).andExpect(status().isNotFound());

        // Y la solicitud sigue intacta para Bob.
        pending(bob).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("responder dos veces la misma solicitud da 409")
    void cannotRespondTwice() throws Exception {
        String requestId = sendRequestOk(alice, bob);

        respond(bob, requestId, true).andExpect(status().isOk());
        respond(bob, requestId, false).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("validacion: correo invalido y body sin 'accept' dan 400")
    void validationRejectsBadInput() throws Exception {
        sendRequest(alice, "noesunemail")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.addresseeEmail").exists());

        String requestId = sendRequestOk(alice, bob);
        mockMvc.perform(patch("/api/friends/requests/" + requestId)
                        .header("Authorization", "Bearer " + bob.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.accept").exists());
    }

    @Test
    @DisplayName("un id que no es UUID da 400, no 500")
    void invalidRequestIdReturnsBadRequest() throws Exception {
        respond(bob, "esto-no-es-un-uuid", true).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("todos los endpoints de amigos exigen token")
    void endpointsRequireAuth() throws Exception {
        mockMvc.perform(get("/api/friends")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/friends/requests/pending")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/friends/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addresseeEmail\":\"a@b.com\"}"))
                .andExpect(status().isUnauthorized());
    }
}
