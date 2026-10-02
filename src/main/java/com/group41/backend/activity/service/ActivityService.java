package com.group41.backend.activity.service;

import com.group41.backend.activity.controller.ActivityDtos;
import com.group41.backend.activity.domain.Activity;
import com.group41.backend.activity.domain.UserActivity;
import com.group41.backend.activity.repository.ActivityRepository;
import com.group41.backend.activity.repository.UserActivityRepository;
import com.group41.backend.activity.service.RecommendationEngine.Candidate;
import com.group41.backend.activity.service.RecommendationEngine.Ranked;
import com.group41.backend.common.ApiException;
import com.group41.backend.user.domain.User;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ActivityService {

    private static final double DEFAULT_SEARCH_RADIUS_KM = 1.0;
    private static final double DEFAULT_RECOMMENDATION_RADIUS_KM = 5.0;
    private static final double MAX_RADIUS_KM = 50.0;
    private static final int MAX_RECOMMENDATIONS = 20;

    private final ActivityRepository activityRepository;
    private final UserActivityRepository userActivityRepository;
    private final RecommendationEngine recommendationEngine;

    public ActivityService(ActivityRepository activityRepository,
                           UserActivityRepository userActivityRepository,
                           RecommendationEngine recommendationEngine) {
        this.activityRepository = activityRepository;
        this.userActivityRepository = userActivityRepository;
        this.recommendationEngine = recommendationEngine;
    }

    @Transactional
    public ActivityDtos.ActivityResponse create(User me, ActivityDtos.CreateActivityRequest request) {
        if (!request.endTime().isAfter(request.startTime())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "El fin debe ser posterior al inicio");
        }
        if (!request.endTime().isAfter(ZonedDateTime.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "La actividad debe terminar en el futuro");
        }

        String description = request.description() == null || request.description().isBlank()
                ? null
                : request.description().trim();

        Activity saved = activityRepository.save(new Activity(
                request.title().trim(),
                description,
                request.category(),
                request.startTime(),
                request.endTime(),
                request.locationName().trim(),
                request.latitude(),
                request.longitude(),
                me.getId()));

        return toResponse(saved, null, 0, false);
    }

    /** Actividades vigentes dentro del radio, de la mas cercana a la mas lejana. */
    @Transactional(readOnly = true)
    public List<ActivityDtos.ActivityResponse> nearby(User me, double lat, double lon, Double radiusKm) {
        validateCoordinates(lat, lon);
        double radius = resolveRadius(radiusKm, DEFAULT_SEARCH_RADIUS_KM);

        Set<UUID> joined = new HashSet<>(userActivityRepository.findActivityIdsByUserId(me.getId()));
        return toResponses(findCandidates(lat, lon, radius), joined);
    }

    /** Actividades cercanas en las que no estoy inscrito, ordenadas por preferencias y distancia. */
    @Transactional(readOnly = true)
    public List<ActivityDtos.RecommendedActivity> recommendations(User me, double lat, double lon, Double radiusKm) {
        validateCoordinates(lat, lon);
        double radius = resolveRadius(radiusKm, DEFAULT_RECOMMENDATION_RADIUS_KM);

        Set<UUID> joined = new HashSet<>(userActivityRepository.findActivityIdsByUserId(me.getId()));
        List<Candidate> candidates = findCandidates(lat, lon, radius).stream()
                .filter(c -> !joined.contains(c.activity().getId()))
                .toList();

        List<Ranked> ranked = RecommendationEngine
                .rank(candidates, recommendationEngine.preferencesOf(me.getId()), radius).stream()
                .limit(MAX_RECOMMENDATIONS)
                .toList();

        List<ActivityDtos.ActivityResponse> responses =
                toResponses(ranked.stream().map(Ranked::candidate).toList(), joined);

        List<ActivityDtos.RecommendedActivity> result = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            result.add(new ActivityDtos.RecommendedActivity(responses.get(i), round(ranked.get(i).score(), 4)));
        }
        return result;
    }

    @Transactional
    public ActivityDtos.ActivityResponse join(User me, UUID activityId) {
        Activity activity = activityRepository.findById(activityId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Actividad no encontrada"));

        if (!activity.getEndTime().isAfter(ZonedDateTime.now())) {
            throw new ApiException(HttpStatus.CONFLICT, "La actividad ya termino");
        }
        if (userActivityRepository.existsByUserIdAndActivityId(me.getId(), activityId)) {
            throw new ApiException(HttpStatus.CONFLICT, "Ya estas inscrito en esta actividad");
        }

        try {
            userActivityRepository.saveAndFlush(new UserActivity(me.getId(), activityId));
        } catch (DataIntegrityViolationException ex) {
            // Dos peticiones simultaneas chocando con la restriccion unica.
            throw new ApiException(HttpStatus.CONFLICT, "Ya estas inscrito en esta actividad");
        }

        recommendationEngine.recalculate(me.getId());

        return toResponse(activity, null, (int) userActivityRepository.countByActivityId(activityId), true);
    }

    @Transactional
    public void leave(User me, UUID activityId) {
        UserActivity inscription = userActivityRepository.findByUserIdAndActivityId(me.getId(), activityId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No estas inscrito en esta actividad"));

        userActivityRepository.delete(inscription);
        userActivityRepository.flush();

        recommendationEngine.recalculate(me.getId());
    }

    // ------------------------------------------------------------------ helpers

    /** Bounding Box en la consulta, distancia exacta en memoria. */
    private List<Candidate> findCandidates(double lat, double lon, double radiusKm) {
        GeoUtils.BoundingBox box = GeoUtils.boundingBox(lat, lon, radiusKm);

        return activityRepository
                .findInBox(box.minLat(), box.maxLat(), box.minLon(), box.maxLon(), ZonedDateTime.now()).stream()
                .map(a -> new Candidate(a, GeoUtils.distanceKm(lat, lon, a.getLatitude(), a.getLongitude())))
                .filter(c -> c.distanceKm() <= radiusKm)
                .sorted(Comparator.comparingDouble(Candidate::distanceKm))
                .toList();
    }

    /** Convierte candidatos en respuestas con una sola consulta para los conteos. */
    private List<ActivityDtos.ActivityResponse> toResponses(List<Candidate> candidates, Set<UUID> joinedIds) {
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<UUID> ids = candidates.stream().map(c -> c.activity().getId()).toList();
        Map<UUID, Long> counts = new HashMap<>();
        userActivityRepository.countByActivityIds(ids)
                .forEach(c -> counts.put(c.getActivityId(), c.getTotal()));

        return candidates.stream()
                .map(c -> {
                    UUID id = c.activity().getId();
                    return toResponse(
                            c.activity(),
                            round(c.distanceKm(), 2),
                            counts.getOrDefault(id, 0L).intValue(),
                            joinedIds.contains(id));
                })
                .toList();
    }

    private static ActivityDtos.ActivityResponse toResponse(Activity a, Double distanceKm,
                                                            int participants, boolean joined) {
        return new ActivityDtos.ActivityResponse(
                a.getId().toString(),
                a.getTitle(),
                a.getDescription(),
                a.getCategory(),
                DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(a.getStartTime()),
                DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(a.getEndTime()),
                a.getLocationName(),
                a.getLatitude(),
                a.getLongitude(),
                distanceKm,
                participants,
                joined);
    }

    private static void validateCoordinates(double lat, double lon) {
        if (!Double.isFinite(lat) || lat < -90 || lat > 90) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "La latitud debe estar entre -90 y 90");
        }
        if (!Double.isFinite(lon) || lon < -180 || lon > 180) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "La longitud debe estar entre -180 y 180");
        }
    }

    private static double resolveRadius(Double radiusKm, double defaultKm) {
        if (radiusKm == null) {
            return defaultKm;
        }
        if (!Double.isFinite(radiusKm) || radiusKm <= 0 || radiusKm > MAX_RADIUS_KM) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "El radio debe ser mayor que 0 y de maximo " + (int) MAX_RADIUS_KM + " km");
        }
        return radiusKm;
    }

    private static double round(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }
}