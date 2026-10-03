package com.group41.backend.activity.controller;

import com.group41.backend.activity.service.ActivityService;
import com.group41.backend.analytics.AnalyticsPublisher;
import com.group41.backend.schedule.service.ScheduleService;
import com.group41.backend.user.domain.User;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/activities")
public class ActivityController {

    private static final int MAX_FREE_MINUTES = 1440;

    private final ActivityService activityService;
    private final ScheduleService scheduleService;
    private final AnalyticsPublisher analytics;

    public ActivityController(ActivityService activityService,
                              ScheduleService scheduleService,
                              AnalyticsPublisher analytics) {
        this.activityService = activityService;
        this.scheduleService = scheduleService;
        this.analytics = analytics;
    }

    /** Crea una actividad. */
    @PostMapping
    public ResponseEntity<ActivityDtos.ActivityResponse> create(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody ActivityDtos.CreateActivityRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(activityService.create(user, request));
    }

    /** Actividades cercanas. radius en km (por defecto 1, maximo 50). */
    @GetMapping
    public ResponseEntity<List<ActivityDtos.ActivityResponse>> nearby(
            @AuthenticationPrincipal User user,
            @RequestParam("lat") double lat,
            @RequestParam("lon") double lon,
            @RequestParam(value = "radius", required = false) Double radius) {
        return ResponseEntity.ok(activityService.nearby(user, lat, lon, radius));
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
            @RequestParam(value = "freeTimeMinutes", required = false) String freeTimeMinutes) {

        // Si join falla (404, 409) la excepcion corta aqui y no se emite nada.
        ActivityDtos.ActivityResponse response = activityService.join(user, id);

        String validRecommendationId = parseUuid(recommendationId);
        if (validRecommendationId != null) {
            analytics.activitySelected(
                    user.getId(),
                    response.id(),
                    response.category().name(),
                    validRecommendationId,
                    parseFreeMinutes(freeTimeMinutes));
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
}