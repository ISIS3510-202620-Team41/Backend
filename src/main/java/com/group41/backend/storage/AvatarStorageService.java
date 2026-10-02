package com.group41.backend.storage;

import com.group41.backend.auth.AuthDtos;
import com.group41.backend.auth.AuthService;
import com.group41.backend.common.ApiException;
import com.group41.backend.user.User;
import com.group41.backend.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.UUID;

/**
 * Guarda la foto de perfil en disco y devuelve el usuario actualizado.
 *
 * En desarrollo los archivos van a una carpeta local servida en /uploads.
 * Para produccion esto se cambia por S3, Cloudinary o Firebase Storage:
 * solo habria que reemplazar los metodos de escritura y borrado.
 */
@Service
public class AvatarStorageService {

    private static final Logger log = LoggerFactory.getLogger(AvatarStorageService.class);

    /** Solo mapas de bits comunes. Nada de SVG: puede llevar scripts dentro. */
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private static final int MAX_DIMENSION = 512;

    private final UserRepository userRepository;
    private final Path uploadDir;
    private final String publicBaseUrl;

    public AvatarStorageService(
            UserRepository userRepository,
            @Value("${app.storage.upload-dir}") String uploadDir,
            @Value("${app.storage.public-base-url}") String publicBaseUrl
    ) {
        this.userRepository = userRepository;
        this.uploadDir = Paths.get(uploadDir).toAbsolutePath().normalize();
        this.publicBaseUrl = publicBaseUrl;
    }

    @Transactional
    public AuthDtos.UserResponse storeAvatar(User user, MultipartFile file) {
        validate(file);

        // Nombre generado por el servidor. Si usaramos el nombre original,
        // alguien podria mandar "../../config.yml" y escribir fuera de la carpeta.
        String filename = UUID.randomUUID() + ".jpg";
        String previousUrl = user.getAvatarUrl();

        try {
            Files.createDirectories(uploadDir);

            BufferedImage original = readImage(file);
            BufferedImage resized = resize(original);

            // Reescribir como JPEG descarta todos los metadatos del original,
            // incluido el EXIF. Eso importa: las fotos de celular traen las
            // coordenadas GPS de donde se tomaron, y publicarlas seria filtrar
            // donde vive el usuario.
            Path target = uploadDir.resolve(filename);
            if (!ImageIO.write(resized, "jpg", target.toFile())) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo guardar la imagen");
            }
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo guardar la imagen");
        }

        user.setAvatarUrl(publicBaseUrl + "/uploads/" + filename);
        userRepository.save(user);

        // El archivo anterior se borra DESPUES de que el nuevo quedo guardado.
        // Al reves, un fallo a mitad dejaria al usuario sin ninguna foto.
        deleteFileFor(previousUrl);

        return AuthService.toUserResponse(user);
    }

    /** Quita la foto del perfil y borra el archivo. */
    @Transactional
    public AuthDtos.UserResponse removeAvatar(User user) {
        String previousUrl = user.getAvatarUrl();

        user.setAvatarUrl(null);
        userRepository.save(user);

        deleteFileFor(previousUrl);

        return AuthService.toUserResponse(user);
    }

    /**
     * Borra el archivo asociado a una URL previa.
     *
     * No lanza si falla: que quede un archivo huerfano es molesto, pero
     * reventar la peticion cuando la foto nueva ya se guardo correctamente
     * seria peor para el usuario.
     */
    private void deleteFileFor(String avatarUrl) {
        if (avatarUrl == null || avatarUrl.isBlank()) {
            return;
        }

        try {
            String filename = Paths.get(java.net.URI.create(avatarUrl).getPath())
                    .getFileName()
                    .toString();

            Path target = uploadDir.resolve(filename).normalize();

            // Defensa en profundidad: aunque el nombre lo genera el servidor,
            // comprobamos que la ruta resuelta siga dentro de la carpeta.
            if (!target.startsWith(uploadDir)) {
                log.warn("Se ignoro un borrado fuera de la carpeta de subidas: {}", avatarUrl);
                return;
            }

            Files.deleteIfExists(target);
        } catch (IOException | IllegalArgumentException ex) {
            log.warn("No se pudo borrar el avatar anterior ({}): {}", avatarUrl, ex.getMessage());
        }
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "No se recibio ninguna imagen");
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType.toLowerCase())) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Formato no permitido. Usa JPG, PNG o WebP");
        }
    }

    /**
     * Si ImageIO no puede decodificarlo, el archivo no era una imagen por mas
     * que el content-type dijera que si. El cliente puede mentir en ese header.
     */
    private BufferedImage readImage(MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            BufferedImage image = ImageIO.read(in);
            if (image == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "El archivo no es una imagen valida");
            }
            return image;
        }
    }

    /**
     * Reduce al lado mayor permitido manteniendo la proporcion, y aplana sobre
     * blanco porque JPEG no soporta transparencia (un PNG transparente se veria
     * con fondo negro si no se hace esto).
     */
    private BufferedImage resize(BufferedImage original) {
        int width = original.getWidth();
        int height = original.getHeight();

        double scale = Math.min(1.0, (double) MAX_DIMENSION / Math.max(width, height));
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));

        BufferedImage output = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = output.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, targetWidth, targetHeight);
            g.drawImage(original, 0, 0, targetWidth, targetHeight, null);
        } finally {
            g.dispose();
        }
        return output;
    }
}
