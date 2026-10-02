package com.group41.backend.activity.controller;

import com.group41.backend.activity.domain.Category;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.ZonedDateTime;

/** Contratos de /api/activities. */
public final class ActivityDtos {

    private ActivityDtos() {
    }

    public record CreateActivityRequest(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 2000) String description,
            @NotNull Category category,
            @NotNull ZonedDateTime startTime,
            @NotNull ZonedDateTime endTime,
            @NotBlank @Size(max = 200) String locationName,
            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude
    ) {
    }

    /**
     * Fechas en ISO 8601 con offset.
     *
     * @param distanceKm   distancia al punto consultado; null cuando no hay punto (crear, unirse)
     * @param participants cuantas personas estan inscritas
     * @param joined       si quien consulta esta inscrito
     */
    public record ActivityResponse(
            String id,
            String title,
            String description,
            Category category,
            String startTime,
            String endTime,
            String locationName,
            double latitude,
            double longitude,
            Double distanceKm,
            int participants,
            boolean joined
    ) {
    }

    /** Actividad recomendada con su puntaje (0 a 1, mayor es mejor). */
    public record RecommendedActivity(ActivityResponse activity, double score) {
    }
}