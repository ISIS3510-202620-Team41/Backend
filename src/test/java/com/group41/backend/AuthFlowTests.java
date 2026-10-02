package com.group41.backend;

import com.group41.backend.auth.LoginRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Feature #15 - Login Authentication")
class AuthFlowTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LoginRateLimiter rateLimiter;

    private static int counter = 0;
    private String email;

    @BeforeEach
    void setUp() {
        // Un correo distinto por test: comparten la misma base en memoria.
        email = "user" + (++counter) + "@test.com";
        rateLimiter.reset(email, "127.0.0.1");
    }

    private String registerBody(String email) {
        return """
                {"email":"%s","password":"micontrasena123","name":"Test User"}
                """.formatted(email);
    }

    private JsonNode register(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(email)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("registro devuelve access token, refresh token y usuario sin hash")
    void registerReturnsTokens() throws Exception {
        JsonNode body = register(email);

        assertThat(body.get("accessToken").asString()).isNotBlank();
        assertThat(body.get("refreshToken").asString()).isNotBlank();
        assertThat(body.get("expiresInSeconds").asInt()).isEqualTo(900);
        assertThat(body.get("user").get("email").asString()).isEqualTo(email);
        // El hash de la contrasena jamas debe salir en una respuesta.
        assertThat(body.toString()).doesNotContain("passwordHash");
    }

    @Test
    @DisplayName("el correo se normaliza a minusculas y no permite duplicados")
    void emailIsNormalized() throws Exception {
        register(email);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(email.toUpperCase())))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("validacion rechaza email invalido, clave corta y nombre vacio")
    void validationRejectsBadInput() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"noesunemail","password":"123","name":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.email").exists())
                .andExpect(jsonPath("$.fields.password").exists())
                .andExpect(jsonPath("$.fields.name").exists());
    }

    @Test
    @DisplayName("contrasena incorrecta y correo inexistente dan el MISMO mensaje")
    void doesNotLeakWhichEmailsExist() throws Exception {
        register(email);

        MvcResult wrongPassword = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"equivocada"}
                                """.formatted(email)))
                .andExpect(status().isUnauthorized())
                .andReturn();

        MvcResult unknownEmail = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"nadie-%s","password":"micontrasena123"}
                                """.formatted(email)))
                .andExpect(status().isUnauthorized())
                .andReturn();

        // Si estos mensajes difirieran, se podrian enumerar los correos registrados.
        assertThat(wrongPassword.getResponse().getContentAsString())
                .contains("Credenciales invalidas");
        assertThat(unknownEmail.getResponse().getContentAsString())
                .contains("Credenciales invalidas");
    }

    @Test
    @DisplayName("/me exige token: sin el da 401, con uno falso tambien")
    void meRequiresValidToken() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer token.falso.aqui"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("/me con token valido devuelve el perfil")
    void meReturnsProfile() throws Exception {
        String token = register(email).get("accessToken").asString();

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    @DisplayName("refresh entrega un par nuevo y revoca el anterior (rotacion)")
    void refreshRotatesToken() throws Exception {
        String firstRefresh = register(email).get("refreshToken").asString();

        MvcResult result = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(firstRefresh)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("refreshToken").asString()).isNotEqualTo(firstRefresh);

        // Reutilizar el token viejo ya no funciona: eso es la rotacion.
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(firstRefresh)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("logout revoca el refresh token")
    void logoutRevokesSession() throws Exception {
        String refresh = register(email).get("refreshToken").asString();

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(refresh)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(refresh)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("tras 5 intentos fallidos el login responde 429")
    void rateLimiterBlocksBruteForce() throws Exception {
        register(email);

        String badLogin = """
                {"email":"%s","password":"equivocada"}
                """.formatted(email);

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(badLogin))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badLogin))
                .andExpect(status().isTooManyRequests());

        // Incluso con la contrasena correcta sigue bloqueado durante la ventana.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"micontrasena123"}
                                """.formatted(email)))
                .andExpect(status().isTooManyRequests());
    }
}
