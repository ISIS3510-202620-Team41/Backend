package com.group41.backend.analytics;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Envia eventos al analytics engine sin bloquear nunca una peticion.
 *
 * Cola acotada y un solo hilo: si el engine esta caido o lento, los eventos
 * se descartan en vez de frenar la API. Perder una metrica es aceptable;
 * hacer esperar al usuario, no.
 */
@Component
public class AnalyticsPublisher {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsPublisher.class);

    private final boolean enabled;
    private final String ingestKey;
    private final RestClient http;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1000),
            runnable -> {
                Thread thread = new Thread(runnable, "analytics-publisher");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.DiscardPolicy());

    public AnalyticsPublisher(
            @Value("${app.analytics.enabled:false}") boolean enabled,
            @Value("${app.analytics.url:http://localhost:8000}") String url,
            @Value("${app.analytics.ingest-key:}") String ingestKey) {
        this.enabled = enabled;
        this.ingestKey = ingestKey;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofSeconds(2));
        this.http = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    public void recommendationShown(UUID userId, String recommendationId, int freeTimeMinutes) {
        send(event("recommendation_shown", userId, Map.of(
                "recommendationId", recommendationId,
                "freeTimeMinutes", freeTimeMinutes)));
    }

    public void activitySelected(UUID userId, String activityId, String category,
                                 String recommendationId, Integer freeTimeMinutes) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("activityId", activityId);
        extra.put("category", category);
        extra.put("recommendationId", recommendationId);
        if (freeTimeMinutes != null) {
            extra.put("freeTimeMinutes", freeTimeMinutes);
        }
        send(event("recommended_activity_selected", userId, extra));
    }

    public void friendAvailabilityUsed(UUID userId, String action) {
        send(event("friend_availability_used", userId, Map.of("action", action)));
    }

    private Map<String, Object> event(String type, UUID userId, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventType", type);
        body.put("eventId", UUID.randomUUID().toString());
        body.put("userId", userId.toString());
        body.put("timestamp", Instant.now().toString());
        body.putAll(extra);
        return body;
    }

    private void send(Map<String, Object> body) {
        if (!enabled) {
            return;
        }
        executor.execute(() -> {
            try {
                RestClient.RequestBodySpec request = http.post().uri("/events");
                if (!ingestKey.isBlank()) {
                    request.header("X-API-Key", ingestKey);
                }
                request.body(body).retrieve().toBodilessEntity();
            } catch (RuntimeException ex) {
                log.warn("No se pudo enviar el evento {} al engine: {}", body.get("eventType"), ex.getMessage());
            }
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}