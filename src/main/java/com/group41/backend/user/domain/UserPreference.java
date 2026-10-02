package com.group41.backend.user.domain;

import com.group41.backend.activity.domain.Category;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/**
 * Puntaje de afinidad de un usuario con una categoria.
 *
 * Es un valor DERIVADO: se calcula a partir de las actividades a las que se
 * ha unido (ver el motor de Time Decay) y se guarda para no recalcularlo en
 * cada peticion de recomendaciones. Si se pierde, se puede reconstruir.
 */
@Entity
@Table(
        name = "user_preferences",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_preference_user_category",
                columnNames = {"user_id", "category"}))
public class UserPreference {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    private Category category;

    @Column(nullable = false)
    private double score;

    protected UserPreference() {
        // Requerido por JPA.
    }

    public UserPreference(UUID userId, Category category, double score) {
        this.userId = userId;
        this.category = category;
        this.score = score;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public Category getCategory() {
        return category;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }
}