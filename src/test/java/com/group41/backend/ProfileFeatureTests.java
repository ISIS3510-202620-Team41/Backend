package com.group41.backend;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Feature #11 - Profile Picture")
class ProfileFeatureTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${app.storage.upload-dir}")
    private String uploadDir;

    private static int counter = 0;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        String email = "avatar" + (++counter) + "@test.com";

        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"micontrasena123","name":"Test User"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();

        token = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asString();
    }

    /** Imagen real, mas grande que el limite, para comprobar el reescalado. */
    private static byte[] jpeg(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, width, height);
        g.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private String uploadAvatar() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "foto.jpg", "image/jpeg", jpeg(2000, 1500));

        MvcResult result = mockMvc.perform(multipart("/api/users/me/avatar")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("avatarUrl").asString();
    }

    private Path fileFor(String avatarUrl) {
        String name = Paths.get(java.net.URI.create(avatarUrl).getPath()).getFileName().toString();
        return Paths.get(uploadDir).toAbsolutePath().normalize().resolve(name);
    }

    @Test
    @DisplayName("subir avatar sin token da 401")
    void uploadRequiresAuth() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "foto.jpg", "image/jpeg", jpeg(100, 100));

        mockMvc.perform(multipart("/api/users/me/avatar").file(file))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("la imagen se reescala a 512 px y pierde los metadatos EXIF")
    void imageIsResizedAndStripped() throws Exception {
        String url = uploadAvatar();
        Path stored = fileFor(url);

        assertThat(Files.exists(stored)).isTrue();

        BufferedImage saved = ImageIO.read(stored.toFile());
        assertThat(Math.max(saved.getWidth(), saved.getHeight())).isEqualTo(512);
        // 2000x1500 reducido manteniendo proporcion.
        assertThat(saved.getWidth()).isEqualTo(512);
        assertThat(saved.getHeight()).isEqualTo(384);

        // El archivo se reescribe como JPEG, asi que no conserva el EXIF del
        // original (donde viajan las coordenadas GPS de la foto).
        byte[] bytes = Files.readAllBytes(stored);
        assertThat(new String(bytes, StandardCharsets.ISO_8859_1)).doesNotContain("Exif");
    }

    @Test
    @DisplayName("reemplazar el avatar borra el archivo anterior")
    void replacingAvatarDeletesOldFile() throws Exception {
        String firstUrl = uploadAvatar();
        Path firstFile = fileFor(firstUrl);
        assertThat(Files.exists(firstFile)).isTrue();

        String secondUrl = uploadAvatar();
        assertThat(secondUrl).isNotEqualTo(firstUrl);

        // Sin esto se acumularian archivos huerfanos con cada cambio de foto.
        assertThat(Files.exists(firstFile)).isFalse();
        assertThat(Files.exists(fileFor(secondUrl))).isTrue();
    }

    @Test
    @DisplayName("borrar el avatar lo quita del perfil y del disco")
    void deleteAvatarRemovesFile() throws Exception {
        String url = uploadAvatar();
        Path file = fileFor(url);

        mockMvc.perform(delete("/api/users/me/avatar")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").doesNotExist());

        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    @DisplayName("un archivo de texto se rechaza con 415")
    void rejectsNonImageContentType() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "nota.txt", "text/plain", "no soy una imagen".getBytes());

        mockMvc.perform(multipart("/api/users/me/avatar")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    @DisplayName("un archivo que miente sobre su content-type se rechaza con 400")
    void rejectsFakeImage() throws Exception {
        // El cliente puede poner el content-type que quiera; por eso ademas se
        // intenta decodificar el contenido de verdad.
        MockMultipartFile file = new MockMultipartFile(
                "file", "falsa.jpg", "image/jpeg", "no soy una imagen".getBytes());

        mockMvc.perform(multipart("/api/users/me/avatar")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /me actualiza nombre y bio, y deja intacto lo que no se envia")
    void updateProfileIsPartial() throws Exception {
        mockMvc.perform(patch("/api/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Sebastian","bio":"Estudiante de Diseno"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Sebastian"))
                .andExpect(jsonPath("$.bio").value("Estudiante de Diseno"));

        // Solo se manda bio: el nombre debe conservarse.
        mockMvc.perform(patch("/api/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bio":"Nueva descripcion"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Sebastian"))
                .andExpect(jsonPath("$.bio").value("Nueva descripcion"));
    }

    @Test
    @DisplayName("editar perfil sin token da 401")
    void updateProfileRequiresAuth() throws Exception {
        mockMvc.perform(patch("/api/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Hacker"}
                                """))
                .andExpect(status().isUnauthorized());
    }
}
