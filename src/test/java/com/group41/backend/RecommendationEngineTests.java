package com.group41.backend;

import com.group41.backend.activity.domain.Activity;
import com.group41.backend.activity.domain.Category;
import com.group41.backend.activity.service.RecommendationEngine;
import com.group41.backend.activity.service.RecommendationEngine.Candidate;
import com.group41.backend.activity.service.RecommendationEngine.Ranked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("Motor de preferencias - decaimiento y ranking")
class RecommendationEngineTests {

    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");

    private static Candidate candidate(String title, Category category, double distanceKm) {
        ZonedDateTime start = ZonedDateTime.of(2026, 10, 5, 10, 0, 0, 0, ZoneId.of("America/Bogota"));
        Activity activity = new Activity(title, null, category, start, start.plusHours(2),
                "Lugar", 4.6, -74.0, null);
        return new Candidate(activity, distanceKm);
    }

    private static String titleOf(Ranked ranked) {
        return ranked.candidate().activity().getTitle();
    }

    @Test
    @DisplayName("sin inscripciones el puntaje es 0")
    void noParticipationsScoresZero() {
        assertThat(RecommendationEngine.affinity(List.of(), NOW)).isZero();
    }

    @Test
    @DisplayName("cada inscripcion aporta 1/(1+dias): lo reciente pesa mas")
    void recentParticipationsWeighMore() {
        List<Instant> joined = List.of(
                NOW,                                 // d=0 -> 1
                NOW.minus(1, ChronoUnit.DAYS),       // d=1 -> 0.5
                NOW.minus(3, ChronoUnit.DAYS));      // d=3 -> 0.25

        assertThat(RecommendationEngine.affinity(joined, NOW)).isCloseTo(1.75, within(0.0001));
    }

    @Test
    @DisplayName("solo cuentan los ultimos 90 dias")
    void onlyTheLast90DaysCount() {
        assertThat(RecommendationEngine.affinity(List.of(NOW.minus(90, ChronoUnit.DAYS)), NOW))
                .isCloseTo(1.0 / 91, within(0.0001));
        assertThat(RecommendationEngine.affinity(List.of(NOW.minus(91, ChronoUnit.DAYS)), NOW))
                .isZero();
    }

    @Test
    @DisplayName("sin preferencias manda solo la distancia")
    void withoutPreferencesDistanceDecides() {
        List<Ranked> ranked = RecommendationEngine.rank(
                List.of(candidate("lejos", Category.DEPORTES, 0.9),
                        candidate("cerca", Category.CULTURA, 0.1)),
                Map.of(), 1.0);

        assertThat(ranked).extracting(RecommendationEngineTests::titleOf)
                .containsExactly("cerca", "lejos");
    }

    @Test
    @DisplayName("una categoria preferida le gana a una mas cercana que no interesa")
    void preferredCategoryBeatsCloserOne() {
        List<Ranked> ranked = RecommendationEngine.rank(
                List.of(candidate("cultura cerca", Category.CULTURA, 0.1),
                        candidate("deporte lejos", Category.DEPORTES, 0.9)),
                Map.of(Category.DEPORTES, 2.0), 1.0);

        // deporte: 0.6*1 + 0.4*0.1 = 0.64   cultura: 0 + 0.4*0.9 = 0.36
        assertThat(ranked).extracting(RecommendationEngineTests::titleOf)
                .containsExactly("deporte lejos", "cultura cerca");
        assertThat(ranked.get(0).score()).isCloseTo(0.64, within(0.0001));
    }
}