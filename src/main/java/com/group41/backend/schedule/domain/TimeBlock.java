package com.group41.backend.schedule.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Un intervalo en el que el usuario esta ocupado (clase, reunion, etc.).
 *
 * Los huecos libres no se guardan: se calculan restando estos bloques de la
 * ventana del dia. Asi no hay que mantener dos fuentes de verdad.
 */
@Entity
@Table(
        name = "time_blocks",
        indexes = @Index(name = "idx_time_block_user_start", columnList = "user_id, start_time"))
public class TimeBlock {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(name = "start_time", nullable = false)
    private ZonedDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private ZonedDateTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScheduleSource source;

    /** Id del evento en el origen (UID del .ics o id de Google). Sirve para no duplicar al resincronizar. */
    @Column(name = "source_event_id", length = 512)
    private String sourceEventId;

    protected TimeBlock() {
        // Requerido por JPA.
    }

    public TimeBlock(UUID userId, String title, ZonedDateTime startTime, ZonedDateTime endTime,
                     ScheduleSource source, String sourceEventId) {
        Objects.requireNonNull(startTime, "startTime");
        Objects.requireNonNull(endTime, "endTime");
        if (!endTime.isAfter(startTime)) {
            throw new IllegalArgumentException("El fin del bloque debe ser posterior al inicio");
        }
        this.userId = userId;
        this.title = title;
        this.startTime = startTime;
        this.endTime = endTime;
        this.source = source;
        this.sourceEventId = sourceEventId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getTitle() {
        return title;
    }

    public ZonedDateTime getStartTime() {
        return startTime;
    }

    public ZonedDateTime getEndTime() {
        return endTime;
    }

    public ScheduleSource getSource() {
        return source;
    }

    public String getSourceEventId() {
        return sourceEventId;
    }
}