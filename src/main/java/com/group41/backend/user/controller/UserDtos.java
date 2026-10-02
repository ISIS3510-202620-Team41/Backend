package com.group41.backend.user.controller;

import com.group41.backend.auth.AuthDtos;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/** Contratos de entrada y salida de /api/friends. */
public final class UserDtos {

    private UserDtos() {
    }

    public record SendFriendRequest(
            @NotBlank @Email String addresseeEmail
    ) {
    }

    /** accept = true acepta, false rechaza. @NotNull evita que un body vacio cuente como rechazo. */
    public record RespondFriendRequest(
            @NotNull Boolean accept
    ) {
    }

    /**
     * Una solicitud de amistad vista desde quien la consulta.
     * "user" es SIEMPRE la otra persona: el destinatario cuando envias,
     * el remitente cuando revisas tus pendientes.
     */
    public record FriendRequestResponse(
            String id,
            String status,
            AuthDtos.UserResponse user,
            Instant createdAt
    ) {
    }
}