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
 * Un cupon comercial que un negocio cercano al campus ofrece a los estudiantes.
 *
 * Es el modelo de ingresos de la app: los comercios pagan por aparecer en el catalogo.
 */
@Entity
@Table(
        name = "deals",
        indexes = {
                @Index(name = "idx_deal_valid_until", columnList = "valid_until"),
                @Index(name = "idx_deal_category", columnList = "category")
        })
public class Deal {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 150)
    private String merchant;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 1000)
    private String description;

    @Column(name = "discount_percent", nullable = false)
    private int discountPercent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DealCategory category;

    /**
     * Cuantos minutos toma aprovechar el cupon.
     *
     * Es el campo que permite cruzar el catalogo con los huecos entre clases del
     * estudiante: un almuerzo con descuento no sirve en un hueco de quince minutos. Sin
     * el, la BQ-11 no podria distinguir "no le interesaba" de "no le daba el tiempo".
     */
    @Column(name = "minutes_needed", nullable = false)
    private int minutesNeeded;

    /** Hasta cuando vale. Null si no vence. */
    @Column(name = "valid_until")
    private ZonedDateTime validUntil;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    protected Deal() {
    }

    public Deal(String merchant, String title, String description, int discountPercent,
                DealCategory category, int minutesNeeded, ZonedDateTime validUntil, String imageUrl) {
        this.merchant = merchant;
        this.title = title;
        this.description = description;
        this.discountPercent = discountPercent;
        this.category = category;
        this.minutesNeeded = minutesNeeded;
        this.validUntil = validUntil;
        this.imageUrl = imageUrl;
    }

    public UUID getId() {
        return id;
    }

    public String getMerchant() {
        return merchant;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public int getDiscountPercent() {
        return discountPercent;
    }

    public DealCategory getCategory() {
        return category;
    }

    public int getMinutesNeeded() {
        return minutesNeeded;
    }

    public ZonedDateTime getValidUntil() {
        return validUntil;
    }

    public String getImageUrl() {
        return imageUrl;
    }
}
