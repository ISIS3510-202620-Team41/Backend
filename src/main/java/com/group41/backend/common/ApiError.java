package com.group41.backend.common;

import java.time.Instant;
import java.util.Map;

/**
 * Forma unica de error para toda la API, para que el cliente Android
 * pueda parsear siempre lo mismo.
 *
 * "fields" solo viene cuando falla la validacion de un DTO.
 */
public record ApiError(
        Instant timestamp,
        int status,
        String message,
        Map<String, String> fields
) {
    public static ApiError of(int status, String message) {
        return new ApiError(Instant.now(), status, message, null);
    }

    public static ApiError of(int status, String message, Map<String, String> fields) {
        return new ApiError(Instant.now(), status, message, fields);
    }
}
