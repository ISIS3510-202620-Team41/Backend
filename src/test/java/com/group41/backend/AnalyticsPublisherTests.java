package com.group41.backend;

import com.group41.backend.analytics.AnalyticsPublisher;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("Analytics - publicador de eventos")
class AnalyticsPublisherTests {

    private record Received(String path, String apiKey, String body) {
    }

    private HttpServer server;
    private final BlockingQueue<Received> received = new LinkedBlockingQueue<>();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void startFakeEngine() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/events", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("X-API-Key"),
                    body));

            byte[] response = "{\"accepted\":1,\"duplicates\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
    }

    @AfterEach
    void stopFakeEngine() {
        server.stop(0);
    }

    private String engineUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private Received next() throws InterruptedException {
        return received.poll(3, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("recommendation_shown llega a /events con la llave y los campos del contrato")
    void recommendationShownIsPosted() throws Exception {
        AnalyticsPublisher publisher = new AnalyticsPublisher(true, engineUrl(), "llave-secreta");

        publisher.recommendationShown(userId, "rec-123", 75);

        Received request = next();
        assertThat(request).isNotNull();
        assertThat(request.path()).isEqualTo("/events");
        assertThat(request.apiKey()).isEqualTo("llave-secreta");
        assertThat(request.body())
                .contains("\"eventType\":\"recommendation_shown\"")
                .contains("\"userId\":\"" + userId + "\"")
                .contains("\"recommendationId\":\"rec-123\"")
                .contains("\"freeTimeMinutes\":75")
                .contains("\"eventId\":\"")
                .contains("\"timestamp\":\"");
    }

    @Test
    @DisplayName("sin llave configurada no se manda el header X-API-Key")
    void noApiKeyHeaderWhenNotConfigured() throws Exception {
        AnalyticsPublisher publisher = new AnalyticsPublisher(true, engineUrl(), "");

        publisher.friendAvailabilityUsed(userId, "viewed");

        Received request = next();
        assertThat(request).isNotNull();
        assertThat(request.apiKey()).isNull();
        assertThat(request.body())
                .contains("\"eventType\":\"friend_availability_used\"")
                .contains("\"action\":\"viewed\"");
    }

    @Test
    @DisplayName("recommended_activity_selected solo incluye freeTimeMinutes si se conoce")
    void activitySelectedFreeTimeIsOptional() throws Exception {
        AnalyticsPublisher publisher = new AnalyticsPublisher(true, engineUrl(), "");

        publisher.activitySelected(userId, "act-1", "DEPORTES", "rec-9", 40);
        publisher.activitySelected(userId, "act-2", "ESTUDIO", "rec-9", null);

        Received withTime = next();
        Received withoutTime = next();
        assertThat(withTime).isNotNull();
        assertThat(withoutTime).isNotNull();

        assertThat(withTime.body())
                .contains("\"eventType\":\"recommended_activity_selected\"")
                .contains("\"activityId\":\"act-1\"")
                .contains("\"category\":\"DEPORTES\"")
                .contains("\"recommendationId\":\"rec-9\"")
                .contains("\"freeTimeMinutes\":40");
        assertThat(withoutTime.body())
                .contains("\"activityId\":\"act-2\"")
                .doesNotContain("freeTimeMinutes");
    }

    @Test
    @DisplayName("cada evento lleva un eventId distinto")
    void eachEventHasItsOwnId() throws Exception {
        AnalyticsPublisher publisher = new AnalyticsPublisher(true, engineUrl(), "");

        publisher.friendAvailabilityUsed(userId, "viewed");
        publisher.friendAvailabilityUsed(userId, "viewed");

        Received first = next();
        Received second = next();
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(eventId(first.body())).isNotEqualTo(eventId(second.body()));
    }

    @Test
    @DisplayName("desactivado, no envia nada")
    void disabledSendsNothing() throws Exception {
        AnalyticsPublisher publisher = new AnalyticsPublisher(false, engineUrl(), "");

        publisher.friendAvailabilityUsed(userId, "viewed");

        assertThat(received.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    @DisplayName("con el engine caido no lanza excepcion ni hace esperar a quien llama")
    void unreachableEngineNeverBlocksOrThrows() {
        String deadUrl = engineUrl();
        server.stop(0); // el puerto queda cerrado

        AnalyticsPublisher publisher = new AnalyticsPublisher(true, deadUrl, "");

        long startedAt = System.nanoTime();
        assertThatCode(() -> {
            publisher.recommendationShown(userId, "rec-1", 10);
            publisher.activitySelected(userId, "act-1", "CULTURA", "rec-1", null);
            publisher.friendAvailabilityUsed(userId, "viewed");
        }).doesNotThrowAnyException();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        // El envio ocurre en otro hilo: quien llama vuelve de inmediato.
        assertThat(elapsedMs).isLessThan(500);
    }

    private static String eventId(String body) {
        int start = body.indexOf("\"eventId\":\"") + "\"eventId\":\"".length();
        return body.substring(start, body.indexOf('"', start));
    }
}