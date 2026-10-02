package com.group41.backend.schedule.service;

/** Google rechazo un codigo o un refresh token (invalido, expirado o revocado). */
public class GoogleAuthException extends RuntimeException {

    public GoogleAuthException(String message) {
        super(message);
    }
}