package com.group41.backend.storage;

import com.group41.backend.activity.controller.ActivityDtos;
import com.group41.backend.activity.domain.Activity;
import com.group41.backend.activity.repository.ActivityRepository;
import com.group41.backend.activity.repository.UserActivityRepository;
import com.group41.backend.activity.service.ActivityService;
import com.group41.backend.common.ApiException;
import com.group41.backend.user.domain.User;
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

@Service
public class ActivityStorageService {

    private static final Logger log = LoggerFactory.getLogger(ActivityStorageService.class);

    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final int MAX_DIMENSION = 1024;

    private final ActivityRepository activityRepository;
    private final UserActivityRepository userActivityRepository;
    private final Path uploadDir;
    private final String publicBaseUrl;

    public ActivityStorageService(
            ActivityRepository activityRepository,
            UserActivityRepository userActivityRepository,
            @Value("${app.storage.upload-dir}") String uploadDir,
            @Value("${app.storage.public-base-url}") String publicBaseUrl
    ) {
        this.activityRepository = activityRepository;
        this.userActivityRepository = userActivityRepository;
        this.uploadDir = Paths.get(uploadDir).toAbsolutePath().normalize();
        this.publicBaseUrl = publicBaseUrl;
    }

    @Transactional
    public ActivityDtos.ActivityResponse storeActivityImage(User user, UUID activityId, MultipartFile file) {
        validate(file);

        Activity activity = activityRepository.findById(activityId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Actividad no encontrada"));

        // Si fue creada por alguien, solo el creador puede cambiar su foto
        if (activity.getCreatedBy() != null && !activity.getCreatedBy().equals(user.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Solo el creador puede cambiar la foto de la actividad");
        }

        String filename = "activity-" + UUID.randomUUID() + ".jpg";
        String previousUrl = activity.getImageUrl();

        try {
            Files.createDirectories(uploadDir);

            BufferedImage original = readImage(file);
            BufferedImage resized = resize(original);

            Path target = uploadDir.resolve(filename);
            if (!ImageIO.write(resized, "jpg", target.toFile())) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo guardar la imagen");
            }
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo guardar la imagen");
        }

        activity.setImageUrl(publicBaseUrl + "/uploads/" + filename);
        activityRepository.save(activity);

        deleteFileFor(previousUrl);

        int participants = (int) userActivityRepository.countByActivityId(activityId);
        boolean joined = userActivityRepository.existsByUserIdAndActivityId(user.getId(), activityId);

        return ActivityService.toResponse(activity, null, participants, joined);
    }

    private void deleteFileFor(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return;
        }

        try {
            String filename = Paths.get(java.net.URI.create(imageUrl).getPath())
                    .getFileName()
                    .toString();

            Path target = uploadDir.resolve(filename).normalize();
            if (!target.startsWith(uploadDir)) {
                log.warn("Se ignoro un borrado fuera de la carpeta de subidas: {}", imageUrl);
                return;
            }

            Files.deleteIfExists(target);
        } catch (IOException | IllegalArgumentException ex) {
            log.warn("No se pudo borrar la imagen anterior ({}): {}", imageUrl, ex.getMessage());
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

    private BufferedImage readImage(MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            BufferedImage image = ImageIO.read(in);
            if (image == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "El archivo no es una imagen valida");
            }
            return image;
        }
    }

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
