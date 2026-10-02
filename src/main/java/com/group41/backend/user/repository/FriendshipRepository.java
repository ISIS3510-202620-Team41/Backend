package com.group41.backend.user.repository;

import com.group41.backend.user.domain.Friendship;
import com.group41.backend.user.domain.FriendshipStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FriendshipRepository extends JpaRepository<Friendship, UUID> {

    /**
     * Busca la relacion entre dos usuarios sin importar quien la envio.
     * La restriccion unica solo cubre (A -> B), asi que el servicio usa esto
     * para evitar que exista tambien (B -> A).
     */
    @Query("""
            select f from Friendship f
            where (f.requesterId = :a and f.addresseeId = :b)
               or (f.requesterId = :b and f.addresseeId = :a)
            """)
    Optional<Friendship> findBetween(@Param("a") UUID a, @Param("b") UUID b);

    /** Solicitudes entrantes de un usuario, las mas recientes primero. */
    List<Friendship> findByAddresseeIdAndStatusOrderByCreatedAtDesc(UUID addresseeId, FriendshipStatus status);

    /** Amistades aceptadas en las que participa el usuario, en cualquiera de los dos lados. */
    @Query("""
            select f from Friendship f
            where f.status = com.group41.backend.user.domain.FriendshipStatus.ACCEPTED
              and (f.requesterId = :userId or f.addresseeId = :userId)
            """)
    List<Friendship> findAcceptedFor(@Param("userId") UUID userId);
}