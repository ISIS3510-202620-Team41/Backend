package com.group41.backend.activity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/** Inscripcion de un usuario en una actividad. La restriccion unica evita inscribirse dos veces. */
@Entity
@Table(
        name = "user_activities",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_activity",
                columnNames = {"user_id", "activity_id"}),
        indexes = @Index(name = "idx_user_activity_activity", columnList = "activity_id"))
public class UserActivity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "activity_id", nullable = false, updatable = false)
    private UUID activityId;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    protected UserActivity() {
        // Requerido por JPA.
    }

    public UserActivity(UUID userId, UUID activityId) {
        this(userId, activityId, Instant.now());
    }

    public UserActivity(UUID userId, UUID activityId, Instant joinedAt) {
        this.userId = userId;
        this.activityId = activityId;
        this.joinedAt = joinedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getActivityId() {
        return activityId;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }
}