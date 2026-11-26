
package com.group41.backend.activity.repository;

import com.group41.backend.activity.domain.Activity;

import java.time.Instant;
import java.util.UUID;

/**
 * Resultado combinado de una inscripción y su actividad.
 */
public record JoinedActivityRow(
        Activity activity,
        UUID enrollmentId,
        Instant joinedAt
) {
}
