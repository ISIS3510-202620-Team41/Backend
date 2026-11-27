package com.group41.backend.deal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * Cada vez que un estudiante ve, abre o redime un cupon, con el contexto de si estaba en
 * un hueco entre clases.
 *
 * ESTA TABLA ES LA BQ-11.
 *
 * La pregunta es si los cupones se interactuan y se redimen mas durante los descansos.
 * Responderla necesita, por cada interaccion, dos cosas: que paso y en que momento del
 * dia del estudiante paso. Las dos viven aqui.
 *
 * POR QUE SE GUARDA AQUI Y NO SOLO EN EL SERVICIO DE ANALITICA
 *
 * La app ya manda eventos al servicio de analitica, y alli es donde viven las metricas de
 * producto. Esta tabla no lo reemplaza: lo complementa, por una razon concreta. Una
 * redencion es una transaccion, no una metrica. El comercio tiene que poder comprobar que
 * el cupon se uso, el estudiante tiene que poder ver su historial, y un evento de
 * analitica perdido en la red no puede significar que una redencion no existio.
 *
 * Que la consulta de la BQ se pueda responder desde aqui es la consecuencia util: el dato
 * ya tenia que estar guardado de todas formas.
 */
@Entity
@Table(
        name = "deal_interactions",
        indexes = {
                @Index(name = "idx_interaction_user", columnList = "user_id"),
                @Index(name = "idx_interaction_deal", columnList = "deal_id"),
                @Index(name = "idx_interaction_occurred", columnList = "occurred_at")
        })
public class DealInteraction {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "deal_id", nullable = false)
    private UUID dealId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InteractionType type;

    /**
     * Si el estudiante estaba en un hueco entre clases. NULL si no se sabe.
     *
     * Es nullable a proposito, y es la decision mas importante de la tabla. Son tres
     * estados: si, no, y no se sabe porque el estudiante no ha importado su horario.
     * Guardar un false en el tercer caso meteria a esos usuarios en el grupo de control y
     * dejaria la BQ sesgada, sin que nada lo avisara. La consulta de agregacion los
     * excluye explicitamente.
     */
    @Column(name = "during_break")
    private Boolean duringBreak;

    /** Minutos que le quedaban del hueco. Null si no estaba en uno o no se sabe. */
    @Column(name = "break_minutes_left")
    private Integer breakMinutesLeft;

    /**
     * El codigo que se le muestra al comercio. Solo en las redenciones.
     *
     * Se genera en el servidor y no en el telefono: si lo generara el cliente, dos
     * dispositivos podrian producir el mismo, y un codigo es lo que el comercio acepta
     * como prueba de la transaccion.
     */
    @Column(name = "redemption_code", length = 20)
    private String redemptionCode;

    @Column(name = "occurred_at", nullable = false)
    private ZonedDateTime occurredAt;

    protected DealInteraction() {
    }

    public DealInteraction(UUID userId, UUID dealId, InteractionType type,
                           Boolean duringBreak, Integer breakMinutesLeft,
                           String redemptionCode, ZonedDateTime occurredAt) {
        this.userId = userId;
        this.dealId = dealId;
        this.type = type;
        this.duringBreak = duringBreak;
        this.breakMinutesLeft = breakMinutesLeft;
        this.redemptionCode = redemptionCode;
        this.occurredAt = occurredAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getDealId() {
        return dealId;
    }

    public InteractionType getType() {
        return type;
    }

    public Boolean getDuringBreak() {
        return duringBreak;
    }

    public Integer getBreakMinutesLeft() {
        return breakMinutesLeft;
    }

    public String getRedemptionCode() {
        return redemptionCode;
    }

    public ZonedDateTime getOccurredAt() {
        return occurredAt;
    }
}
