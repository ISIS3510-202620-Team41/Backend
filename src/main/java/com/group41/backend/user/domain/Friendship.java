package com.group41.backend.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * Relacion de amistad entre dos usuarios.
 *
 * Es una sola fila por pareja: quien la envio es el "requester" y quien la
 * recibe es el "addressee". Una vez aceptada la amistad es simetrica, asi que
 * para listar los amigos de alguien hay que mirar ambas columnas.
 *
 * Se guardan los ids (no una relacion @ManyToOne) a proposito: asi el modulo
 * de amigos no arrastra la entidad User completa en cada consulta.
 */
@Entity
@Table(
        name = "friendships",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_friendship_pair",
                columnNames = {"requester_id", "addressee_id"}),
        indexes = {
                @Index(name = "idx_friendship_addressee_status", columnList = "addressee_id, status"),
                @Index(name = "idx_friendship_requester_status", columnList = "requester_id, status")
        })
public class Friendship {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "requester_id", nullable = false, updatable = false)
    private UUID requesterId;

    @Column(name = "addressee_id", nullable = false, updatable = false)
    private UUID addresseeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FriendshipStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Friendship() {
        // Requerido por JPA.
    }

    public Friendship(UUID requesterId, UUID addresseeId) {
        this.requesterId = requesterId;
        this.addresseeId = addresseeId;
        this.status = FriendshipStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void accept() {
        changeStatus(FriendshipStatus.ACCEPTED);
    }

    public void reject() {
        changeStatus(FriendshipStatus.REJECTED);
    }

    private void changeStatus(FriendshipStatus newStatus) {
        this.status = newStatus;
        this.updatedAt = Instant.now();
    }

    /** Dado uno de los dos extremos, devuelve el id del otro. */
    public UUID otherParty(UUID userId) {
        return requesterId.equals(userId) ? addresseeId : requesterId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRequesterId() {
        return requesterId;
    }

    public UUID getAddresseeId() {
        return addresseeId;
    }

    public FriendshipStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}