package com.group41.backend.schedule.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * Autorizacion de Google de un usuario. Solo se guarda el refresh token, y
 * siempre cifrado: con el se obtienen access tokens nuevos sin pedirle nada al usuario.
 */
@Entity
@Table(
        name = "google_credentials",
        uniqueConstraints = @UniqueConstraint(name = "uk_google_credential_user", columnNames = "user_id"))
public class GoogleCredential {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Base64(iv + texto cifrado). Nunca el token en claro. */
    @Column(name = "encrypted_refresh_token", nullable = false, length = 2048)
    private String encryptedRefreshToken;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected GoogleCredential() {
        // Requerido por JPA.
    }

    public GoogleCredential(UUID userId, String encryptedRefreshToken) {
        this.userId = userId;
        this.encryptedRefreshToken = encryptedRefreshToken;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getEncryptedRefreshToken() {
        return encryptedRefreshToken;
    }

    public void setEncryptedRefreshToken(String encryptedRefreshToken) {
        this.encryptedRefreshToken = encryptedRefreshToken;
        this.updatedAt = Instant.now();
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}