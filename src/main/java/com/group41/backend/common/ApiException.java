package com.group41.backend.common;

import org.springframework.http.HttpStatus;

/**
 * Excepcion de negocio con el status HTTP que le corresponde.
 * El GlobalExceptionHandler la traduce a una respuesta JSON uniforme.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
