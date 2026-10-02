package com.group41.backend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** La app debe arrancar y funcionar aunque no haya credenciales de Google. */
@SpringBootTest(properties = "app.google.client-id=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Google Calendar sin configurar")
class GoogleNotConfiguredTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("sin credenciales los endpoints de Google responden 503 con un mensaje claro")
    void returnsServiceUnavailable() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"notconfigured@test.com","password":"micontrasena123","name":"Test User"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String token = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asString();

        mockMvc.perform(post("/api/schedules/sync/google")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authCode\":\"x\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Google Calendar no esta configurado en el servidor"));

        mockMvc.perform(post("/api/schedules/sync/google/refresh")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable());
    }
}