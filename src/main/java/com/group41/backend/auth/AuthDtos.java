package com.group41.backend.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Contratos de entrada y salida de /api/auth.
 * Estos son los JSON exactos que el cliente Android debe enviar y esperar.
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, max = 72, message = "La contrasena debe tener entre 8 y 72 caracteres")
            String password,
            @NotBlank @Size(max = 80) String name
    ) {
    }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password
    ) {
    }

    public record RefreshRequest(
            @NotBlank String refreshToken
    ) {
    }

    /** Lo que recibe el cliente tras registrarse, iniciar sesion o refrescar. */
    public record AuthResponse(
            String accessToken,
            String refreshToken,
            long expiresInSeconds,
            UserResponse user
    ) {
    }

    /** Vista publica del usuario. Nunca incluye el hash de la contrasena. */
    public record UserResponse(
            String id,
            String email,
            String name,
            String bio,
            String avatarUrl
    ) {
    }

    /** Edicion de perfil: los campos nulos se dejan como estaban. */
    public record UpdateProfileRequest(
            @Size(max = 80) String name,
            @Size(max = 300) String bio
    ) {
    }
}
