package com.group41.backend.activity.controller;

import java.util.List;
import java.util.UUID;

import com.group41.backend.activity.service.ActivityService;
import com.group41.backend.analytics.AnalyticsPublisher;
import com.group41.backend.schedule.service.ScheduleService;
import com.group41.backend.storage.ActivityStorageService;
import com.group41.backend.user.domain.User;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;

import com.group41.backend.activity.service.ActivityService;
import com.group41.backend.analytics.AnalyticsPublisher;
import com.group41.backend.schedule.service.ScheduleService;
import com.group41.backend.user.domain.User;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/activities")
public class ActivityController {

    private static final int MAX_FREE_MINUTES = 1440;
    private static final String CACHE_HEADER_VALUE = "public, max-age=300";

    private final ActivityService activityService;
    private final ScheduleService scheduleService;
    private final AnalyticsPublisher analytics;
    private final ActivityStorageService activityStorageService;

    public ActivityController(ActivityService activityService,
                              ScheduleService scheduleService,
                              AnalyticsPublisher analytics,
                              ActivityStorageService activityStorageService) {
        this.activityService = activityService;
        this.scheduleService = scheduleService;
        this.analytics = analytics;
        this.activityStorageService = activityStorageService;
    }

    /** Crea una actividad. */
    @PostMapping
    public ResponseEntity<ActivityDtos.ActivityResponse> create(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody ActivityDtos.CreateActivityRequest request,
            @RequestParam (value = "source", required = false) String source) {
        ActivityDtos.ActivityResponse response = activityService.create(user, request);
        if ("friend_availability".equals(source)) {
            analytics.friendAvailabilityUsed(
                    user.getId(),
            "plan_created"
            );
        }

        return ResponseEntity.status(HttpStatus.CREATED)
        .body(response);   
    }

    /** Obtiene el detalle de una actividad por ID. */
    @GetMapping("/{id}")
    public ResponseEntity<ActivityDtos.ActivityResponse> getById(
            @AuthenticationPrincipal User user,
            @PathVariable UUID id,
            @RequestParam(value = "lat", required = false) Double lat,
            @RequestParam(value = "lon", required = false) Double lon) {
        return ResponseEntity.ok()
                .header("Cache-Control", CACHE_HEADER_VALUE)
                .body(activityService.getById(user, id, lat, lon));
    }

    /** Sube una imagen para la actividad. */
    @PostMapping(value = "/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ActivityDtos.ActivityResponse> uploadImage(
            @AuthenticationPrincipal User user,
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(activityStorageService.storeActivityImage(user, id, file));
    }

    /** Actividades cercanas. radius en km (por defecto 1, maximo 50). */
    @GetMapping
    public ResponseEntity<List<ActivityDtos.ActivityResponse>> nearby(
            @AuthenticationPrincipal User user,
            @RequestParam("lat") double lat,
            @RequestParam("lon") double lon,
            @RequestParam(value = "radius", required = false) Double radius) {
        return ResponseEntity.ok()
                .header("Cache-Control", CACHE_HEADER_VALUE)
                .body(activityService.nearby(user, lat, lon, radius));
    }

    /**
     * Recomendaciones segun distancia y preferencias. radius en km (por defecto 5).
     *
     * El cuerpo sigue siendo la lista de siempre. El id de esta carga y los minutos
     * libres viajan en headers, asi ningun cliente existente se rompe. La app debe
     * devolverlos en el join si el usuario se une desde esta lista.
     */
    @GetMapping("/recommendations")
    public ResponseEntity<List<ActivityDtos.RecommendedActivity>> recommendations(
            @AuthenticationPrincipal User user,
            @RequestParam("lat") double lat,
            @RequestParam("lon") double lon,
            @RequestParam(value = "radius", required = false) Double radius,
            @RequestParam(value = "tz", required = false) String tz) {

        // Primero lo barato: una zona invalida da 400 antes de rankear nada.
        int freeMinutes = scheduleService.freeMinutesNow(user.getId(), tz);

        List<ActivityDtos.RecommendedActivity> result =
                activityService.recommendations(user, lat, lon, radius);
        String recommendationId = UUID.randomUUID().toString();

        // Una lista vacia no cuenta como recomendacion mostrada.
        if (!result.isEmpty()) {
            analytics.recommendationShown(user.getId(), recommendationId, freeMinutes);
        }

        return ResponseEntity.ok()
                .header("Cache-Control", CACHE_HEADER_VALUE)
                .header("X-Recommendation-Id", recommendationId)
                .header("X-Free-Time-Minutes", String.valueOf(freeMinutes))
                .body(result);
    }

    /**
     * Inscribe al usuario y actualiza sus preferencias.
     *
     * recommendationId y freeTimeMinutes los manda la app solo si el usuario se unio
     * desde una lista de recomendaciones. Son datos de analitica: si vienen mal se
     * ignoran, nunca impiden unirse.
     */
    @PostMapping("/{id}/join")
    public ResponseEntity<ActivityDtos.ActivityResponse> join(
            @AuthenticationPrincipal User user,
            @PathVariable UUID id,
            @RequestParam(value = "recommendationId", required = false) String recommendationId,
            @RequestParam(value = "freeTimeMinutes", required = false) String freeTimeMinutes,
            @RequestParam(value = "source", required = false) String source) {

        // Si join falla (404, 409) la excepcion corta aqui y no se emite nada.
        ActivityDtos.ActivityResponse response = activityService.join(user, id);

        if ("friend_availability".equals(source)) {
            analytics.friendAvailabilityUsed(
                user.getId(),
        "plan_joined"
        );
        }

        String validRecommendationId = parseUuid(recommendationId);
        if (validRecommendationId != null) {
            Integer activityDurationMinutes = parseActivityDurationMinutes(
                    response.startTime(),
                    response.endTime());

            analytics.activitySelected(
                user.getId(),
                response.id(),
                response.category().name(),
                validRecommendationId,
                parseFreeMinutes(freeTimeMinutes),
                activityDurationMinutes,
                response.title(),
                "Gratis");
        }
        return ResponseEntity.ok(response);
    }

    /** Cancela la inscripcion. */
    @DeleteMapping("/{id}/leave")
    public ResponseEntity<Void> leave(
            @AuthenticationPrincipal User user,
            @PathVariable UUID id) {
        activityService.leave(user, id);
        return ResponseEntity.noContent().build();
    }

    /** Devuelve el UUID normalizado, o null si no lo es. */
    private static String parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim()).toString();
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Minutos entre 0 y 1440, o null si no es un entero valido. */
    private static Integer parseFreeMinutes(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int minutes = Integer.parseInt(value.trim());
            return minutes >= 0 && minutes <= MAX_FREE_MINUTES ? minutes : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Calculates the activity duration in minutes using its start and end times.
     * Returns null if the dates are invalid or the duration is outside the valid range.
     */
    private static Integer parseActivityDurationMinutes(String startTime, String endTime) {
        if (startTime == null || endTime == null) {
            return null;
        }

        try {
            ZonedDateTime start = ZonedDateTime.parse(startTime);
            ZonedDateTime end = ZonedDateTime.parse(endTime);

            long minutes = Duration.between(start, end).toMinutes();

            if (minutes < 0 || minutes > Integer.MAX_VALUE) {
                return null;
            }

            return (int) minutes;
        } catch (DateTimeParseException | ArithmeticException ex) {
            return null;
        }
    }

}
