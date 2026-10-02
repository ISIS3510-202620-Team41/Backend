package com.group41.backend.activity.service;

import com.group41.backend.activity.domain.Activity;
import com.group41.backend.activity.domain.Category;
import com.group41.backend.activity.domain.UserActivity;
import com.group41.backend.activity.repository.ActivityRepository;
import com.group41.backend.activity.repository.UserActivityRepository;
import com.group41.backend.user.domain.UserPreference;
import com.group41.backend.user.repository.UserPreferenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Preferencias por decaimiento temporal y ranking de recomendaciones.
 *
 * Puntaje de una categoria: P_c = suma de 1 / (1 + d_i), donde d_i son los dias
 * desde que el usuario se unio a cada actividad de esa categoria, en los
 * ultimos 90 dias. Las inscripciones recientes pesan mas.
 */
@Service
public class RecommendationEngine {

    static final int WINDOW_DAYS = 90;
    static final double PREFERENCE_WEIGHT = 0.6;
    static final double PROXIMITY_WEIGHT = 0.4;

    public record Candidate(Activity activity, double distanceKm) {
    }

    public record Ranked(Candidate candidate, double score) {
    }

    private final UserActivityRepository userActivityRepository;
    private final ActivityRepository activityRepository;
    private final UserPreferenceRepository userPreferenceRepository;

    public RecommendationEngine(UserActivityRepository userActivityRepository,
                                ActivityRepository activityRepository,
                                UserPreferenceRepository userPreferenceRepository) {
        this.userActivityRepository = userActivityRepository;
        this.activityRepository = activityRepository;
        this.userPreferenceRepository = userPreferenceRepository;
    }

    /** P_c para una lista de fechas de inscripcion. Ignora lo que tenga mas de 90 dias. */
    public static double affinity(Collection<Instant> joinedAt, Instant now) {
        double sum = 0;
        for (Instant joined : joinedAt) {
            long days = Math.max(0, ChronoUnit.DAYS.between(joined, now));
            if (days <= WINDOW_DAYS) {
                sum += 1.0 / (1 + days);
            }
        }
        return sum;
    }

    /**
     * Ordena candidatos combinando afinidad con la categoria y cercania.
     * Sin preferencias (usuario nuevo) manda solo la distancia.
     */
    public static List<Ranked> rank(List<Candidate> candidates, Map<Category, Double> preferences, double radiusKm) {
        double maxPreference = preferences.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);

        return candidates.stream()
                .map(c -> {
                    double preference = maxPreference > 0
                            ? preferences.getOrDefault(c.activity().getCategory(), 0.0) / maxPreference
                            : 0;
                    double proximity = Math.min(1.0, Math.max(0.0, 1.0 - c.distanceKm() / radiusKm));
                    return new Ranked(c, PREFERENCE_WEIGHT * preference + PROXIMITY_WEIGHT * proximity);
                })
                .sorted(Comparator.comparingDouble(Ranked::score).reversed()
                        .thenComparingDouble(r -> r.candidate().distanceKm()))
                .toList();
    }

    /** Puntajes guardados del usuario, por categoria. */
    @Transactional(readOnly = true)
    public Map<Category, Double> preferencesOf(UUID userId) {
        Map<Category, Double> result = new EnumMap<>(Category.class);
        userPreferenceRepository.findByUserId(userId)
                .forEach(p -> result.put(p.getCategory(), p.getScore()));
        return result;
    }

    /**
     * Reconstruye los puntajes del usuario desde sus inscripciones. Se llama al
     * unirse y al salir: calcular desde cero evita que el valor guardado se
     * desfase del real.
     */
    @Transactional
    public void recalculate(UUID userId) {
        Instant now = Instant.now();

        List<UserActivity> recent = userActivityRepository
                .findByUserIdAndJoinedAtAfter(userId, now.minus(WINDOW_DAYS + 1L, ChronoUnit.DAYS));

        Map<UUID, Activity> activities = activityRepository
                .findAllById(recent.stream().map(UserActivity::getActivityId).toList()).stream()
                .collect(Collectors.toMap(Activity::getId, Function.identity()));

        Map<Category, List<Instant>> byCategory = new EnumMap<>(Category.class);
        for (UserActivity inscription : recent) {
            Activity activity = activities.get(inscription.getActivityId());
            if (activity != null) {
                byCategory.computeIfAbsent(activity.getCategory(), k -> new ArrayList<>())
                        .add(inscription.getJoinedAt());
            }
        }

        Map<Category, UserPreference> existing = new HashMap<>();
        userPreferenceRepository.findByUserId(userId).forEach(p -> existing.put(p.getCategory(), p));

        for (Category category : Category.values()) {
            double score = affinity(byCategory.getOrDefault(category, List.of()), now);
            UserPreference preference = existing.get(category);
            if (preference == null) {
                if (score > 0) {
                    userPreferenceRepository.save(new UserPreference(userId, category, score));
                }
            } else {
                preference.setScore(score);
                userPreferenceRepository.save(preference);
            }
        }
    }
}