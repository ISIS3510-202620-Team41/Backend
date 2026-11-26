
package com.group41.backend.activity.controller;

import com.group41.backend.activity.domain.Category;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.ZonedDateTime;
import java.util.List;

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
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
            @Size(max = 500) String imageUrl,
            @Size(max = 50) String price,
            @Min(1) @Max(10000) Integer capacity,
            @Size(max = 2000) String aboutLocation
    ) {
    }

    /**
     * Fechas en ISO 8601 con offset.
     */
    public record ActivityResponse(
            String id,
            String title,
            String description,
            Category category,
            String startTime,
            String endTime,
            int durationMinutes,
            String locationName,
            double latitude,
            double longitude,
            Double distanceKm,
            int participants,
            Integer capacity,
            String price,
            String aboutLocation,
            String imageUrl,
            boolean joined
    ) {
    }

    /** Actividad recomendada con su puntaje (0 a 1, mayor es mejor). */
    public record RecommendedActivity(ActivityResponse activity, double score) {
    }

    /** Respuesta genérica para endpoints paginados. */
    public record PageResponse<T>(
            List<T> content,
            int page,
            int size,
            long totalElements,
            int totalPages,
            boolean hasNext
    ) {
    }

    /** Información de la inscripción del usuario. */
    public record EnrollmentResponse(
            String id,
            String joinedAt,
            String status
    ) {
    }

    /** Actividad junto con los datos de la inscripción correspondiente. */
    public record JoinedActivityResponse(
            ActivityResponse activity,
            EnrollmentResponse enrollment
    ) {
    }
}
