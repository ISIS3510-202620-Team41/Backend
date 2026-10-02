package com.group41.backend.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Frena la fuerza bruta sobre /api/auth/login.
 *
 * Cuenta intentos por combinacion de correo + IP dentro de una ventana movil.
 * Se usan los dos juntos a proposito:
 *   - solo por IP castigaria a todo un campus tras un NAT compartido
 *   - solo por correo permitiria a un atacante bloquear la cuenta de otro
 *     a punta de intentos fallidos (denegacion de servicio contra el usuario)
 *
 * LIMITACION CONOCIDA: el contador vive en memoria. Se pierde al reiniciar y
 * no se comparte entre instancias. Para una sola instancia es suficiente; si
 * algun dia escalan a varias, esto debe moverse a Redis.
 */
@Component
public class LoginRateLimiter {

    private final Map<String, Window> attempts = new ConcurrentHashMap<>();
    private final int maxAttempts;
    private final Duration window;

    public LoginRateLimiter(
            @Value("${app.security.login.max-attempts:5}") int maxAttempts,
            @Value("${app.security.login.window-seconds:900}") long windowSeconds
    ) {
        this.maxAttempts = maxAttempts;
        this.window = Duration.ofSeconds(windowSeconds);
    }

    /** Devuelve false si ya se agotaron los intentos de esta ventana. */
    public boolean tryConsume(String email, String clientIp) {
        String key = key(email, clientIp);
        Instant now = Instant.now();

        Window updated = attempts.compute(key, (k, current) -> {
            if (current == null || current.startedAt().plus(window).isBefore(now)) {
                return new Window(now, 1);
            }
            return new Window(current.startedAt(), current.count() + 1);
        });

        return updated.count() <= maxAttempts;
    }

    /** Se llama tras un login correcto. */
    public void reset(String email, String clientIp) {
        attempts.remove(key(email, clientIp));
    }

    private String key(String email, String clientIp) {
        return email + "|" + clientIp;
    }

    private record Window(Instant startedAt, int count) {
    }
}
