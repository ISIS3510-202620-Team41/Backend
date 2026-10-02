package com.group41.backend.auth;

import com.group41.backend.user.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findAllByUserAndRevokedAtIsNull(User user);

    /** Limpieza periodica: los expirados ya no sirven para nada. */
    void deleteAllByExpiresAtBefore(Instant cutoff);
}
