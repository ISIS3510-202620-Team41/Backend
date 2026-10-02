package com.group41.backend.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.group41.backend.user.User;

import java.time.Instant;
import java.util.UUID;

/**
 * Refresh token revocable.
 *
 * A diferencia del access token (que es un JWT autocontenido y no se puede
 * anular antes de que expire), este vive en base de datos: para invalidarlo
 * basta marcarlo como revocado.
 *
 * Se guarda solo el HASH del token, nunca el valor. Si alguien lee esta tabla
 * no puede suplantar a nadie, igual que con las contrasenas.
 */
@Entity
@Table(
        name = "refresh_tokens",
        indexes = @Index(name = "idx_refresh_token_hash", columnList = "token_hash")
)
public class RefreshToken {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RefreshToken() {
        // Requerido por JPA.
    }

    public RefreshToken(String tokenHash, User user, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.user = user;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public User getUser() {
        return user;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void revoke() {
        this.revokedAt = Instant.now();
    }

    /** Sirve solo si no fue revocado y no ha expirado. */
    public boolean isUsable() {
        return revokedAt == null && expiresAt.isAfter(Instant.now());
    }
}
