package com.group41.backend.auth;

import com.group41.backend.common.ApiException;
import com.group41.backend.user.domain.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Emite, valida y revoca refresh tokens.
 *
 * El token NO es un JWT: es un valor aleatorio opaco. Un JWT seria innecesario
 * aqui porque de todos modos hay que consultar la base de datos para saber si
 * fue revocado, y un valor aleatorio no filtra nada si alguien lo intercepta.
 */
@Service
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final RefreshTokenRepository repository;
    private final long expirationSeconds;

    public RefreshTokenService(
            RefreshTokenRepository repository,
            @Value("${app.jwt.refresh-expiration-seconds}") long expirationSeconds
    ) {
        this.repository = repository;
        this.expirationSeconds = expirationSeconds;
    }

    /**
     * Crea un token nuevo y guarda su hash.
     * Devuelve el valor en claro, que es lo unico que vera el cliente: el
     * servidor no lo puede recuperar despues.
     */
    @Transactional
    public String issue(User user) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = ENCODER.encodeToString(bytes);

        repository.save(new RefreshToken(
                hash(token),
                user,
                Instant.now().plusSeconds(expirationSeconds)
        ));

        return token;
    }

    /**
     * Valida el token y lo rota: revoca el usado y emite uno nuevo.
     *
     * La rotacion importa porque un refresh token vive semanas. Si se usa una
     * sola vez, un atacante que lo robe tiene una ventana muy corta, y en
     * cuanto el usuario legitimo refresque, el robado deja de servir.
     */
    @Transactional
    public RotationResult rotate(String rawToken) {
        RefreshToken stored = repository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Sesion invalida"));

        if (!stored.isUsable()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Sesion expirada");
        }

        stored.revoke();
        repository.save(stored);

        User user = stored.getUser();
        return new RotationResult(user, issue(user));
    }

    /** Cierra la sesion de un dispositivo. Idempotente a proposito. */
    @Transactional
    public void revoke(String rawToken) {
        repository.findByTokenHash(hash(rawToken)).ifPresent(token -> {
            if (token.getRevokedAt() == null) {
                token.revoke();
                repository.save(token);
            }
        });
    }

    /** Cierra la sesion en todos los dispositivos (cambio de contrasena, robo). */
    @Transactional
    public void revokeAllForUser(User user) {
        repository.findAllByUserAndRevokedAtIsNull(user).forEach(token -> {
            token.revoke();
            repository.save(token);
        });
    }

    /**
     * SHA-256 y no BCrypt.
     *
     * BCrypt es lento a proposito, lo cual es correcto para contrasenas (que
     * son cortas y adivinables) pero aqui seria contraproducente: habria que
     * comparar contra cada fila de la tabla porque el salt impide buscar por
     * hash. Un token de 256 bits aleatorios no es adivinable, asi que un hash
     * rapido y deterministico es lo adecuado y permite el indice.
     */
    private String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no disponible", ex);
        }
    }

    public long getExpirationSeconds() {
        return expirationSeconds;
    }

    public record RotationResult(User user, String refreshToken) {
    }
}
