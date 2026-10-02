package com.group41.backend.activity.controller;

import com.group41.backend.activity.service.ActivityService;
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

    private final ActivityService activityService;

    public ActivityController(ActivityService activityService) {
        this.activityService = activityService;
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

    /** Recomendaciones segun distancia y preferencias. radius en km (por defecto 5). */
    @GetMapping("/recommendations")
    public ResponseEntity<List<ActivityDtos.RecommendedActivity>> recommendations(
            @AuthenticationPrincipal User user,
            @RequestParam("lat") double lat,
            @RequestParam("lon") double lon,
            @RequestParam(value = "radius", required = false) Double radius) {
        return ResponseEntity.ok(activityService.recommendations(user, lat, lon, radius));
    }

    /** Inscribe al usuario y actualiza sus preferencias. */
    @PostMapping("/{id}/join")
    public ResponseEntity<ActivityDtos.ActivityResponse> join(
            @AuthenticationPrincipal User user,
            @PathVariable UUID id) {
        return ResponseEntity.ok(activityService.join(user, id));
    }

    /** Cancela la inscripcion. */
    @DeleteMapping("/{id}/leave")
    public ResponseEntity<Void> leave(
            @AuthenticationPrincipal User user,
            @PathVariable UUID id) {
        activityService.leave(user, id);
        return ResponseEntity.noContent().build();
    }
}