package com.group41.backend.schedule.service;

import com.group41.backend.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** Cliente HTTP de OAuth2 y Google Calendar API v3. */
@Component
public class RestGoogleCalendarApi implements GoogleCalendarApi {

    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String API_BASE = "https://www.googleapis.com/calendar/v3";
    private static final int MAX_PAGES = 8;

    /** Una pagina de eventos y, si hay mas, el token para pedir la siguiente. */
    public record Page(List<RawEvent> events, String nextPageToken) {
    }

    /** Una pagina de calendarios y, si hay mas, el token para pedir la siguiente. */
    public record CalendarPage(List<RawCalendar> calendars, String nextPageToken) {
    }

    private final RestClient http;
    private final ObjectMapper objectMapper;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    public RestGoogleCalendarApi(
            ObjectMapper objectMapper,
            @Value("${app.google.client-id:}") String clientId,
            @Value("${app.google.client-secret:}") String clientSecret,
            @Value("${app.google.redirect-uri:}") String redirectUri
    ) {
        this.objectMapper = objectMapper;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;

        // Sin timeouts, un Google lento dejaria colgadas las peticiones del usuario.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public Tokens exchangeAuthCode(String authCode) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", authCode);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        // Los codigos de apps nativas no llevan redirect_uri; los de flujo web si.
        if (!redirectUri.isBlank()) {
            form.add("redirect_uri", redirectUri);
        }

        JsonNode body = postToken(form);
        String accessToken = text(body, "access_token");
        if (accessToken == null) {
            throw badGateway("Google no entrego un access token");
        }
        return new Tokens(accessToken, text(body, "refresh_token"));
    }

    @Override
    public String refreshAccessToken(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);

        String accessToken = text(postToken(form), "access_token");
        if (accessToken == null) {
            throw badGateway("Google no entrego un access token");
        }
        return accessToken;
    }

    @Override
    public List<RawCalendar> listCalendars(String accessToken) {
        List<RawCalendar> calendars = new ArrayList<>();
        String pageToken = null;

        for (int page = 0; page < MAX_PAGES; page++) {
            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(API_BASE)
                    .pathSegment("users", "me", "calendarList")
                    .queryParam("maxResults", 250);
            if (pageToken != null) {
                builder.queryParam("pageToken", pageToken);
            }

            JsonNode root = getJson(builder.build().encode().toUri(), accessToken,
                    "Falta el permiso para leer tu lista de calendarios: conecta Google de nuevo "
                            + "pidiendo tambien calendar.calendarlist.readonly");

            CalendarPage parsed = parseCalendarPage(root);
            calendars.addAll(parsed.calendars());
            pageToken = parsed.nextPageToken();
            if (pageToken == null) {
                break;
            }
        }
        return calendars;
    }

    @Override
    public List<RawEvent> listEvents(String accessToken, String calendarId, Instant from, Instant to) {
        List<RawEvent> events = new ArrayList<>();
        String pageToken = null;

        for (int page = 0; page < MAX_PAGES; page++) {
            // El id de un calendario puede traer '@' o '#': va como segmento de ruta y se codifica.
            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(API_BASE)
                    .pathSegment("calendars", calendarId, "events")
                    .queryParam("timeMin", from.truncatedTo(ChronoUnit.SECONDS).toString())
                    .queryParam("timeMax", to.truncatedTo(ChronoUnit.SECONDS).toString())
                    .queryParam("singleEvents", "true") // Google expande las recurrencias
                    .queryParam("orderBy", "startTime")
                    .queryParam("showDeleted", "false")
                    .queryParam("maxResults", 2500);
            if (pageToken != null) {
                builder.queryParam("pageToken", pageToken);
            }

            JsonNode root = getJson(builder.build().encode().toUri(), accessToken,
                    "Google nego el permiso para leer los eventos: revisa que se concedio "
                            + "calendar.events.readonly");

            Page parsed = parsePage(root);
            events.addAll(parsed.events());
            pageToken = parsed.nextPageToken();
            if (pageToken == null) {
                break;
            }
        }
        return events;
    }

    /** Interpreta una pagina de events.list. Publico para poder probarlo. */
    public static Page parsePage(JsonNode root) {
        List<RawEvent> events = new ArrayList<>();
        JsonNode items = root.get("items");
        if (items != null) {
            for (JsonNode item : items) {
                events.add(parseEvent(item));
            }
        }
        return new Page(events, text(root, "nextPageToken"));
    }

    /** Interpreta una pagina de calendarList.list. Publico para poder probarlo. */
    public static CalendarPage parseCalendarPage(JsonNode root) {
        List<RawCalendar> calendars = new ArrayList<>();
        JsonNode items = root.get("items");
        if (items != null) {
            for (JsonNode item : items) {
                String override = text(item, "summaryOverride");
                calendars.add(new RawCalendar(
                        text(item, "id"),
                        override != null ? override : text(item, "summary"),
                        flag(item, "primary"),
                        flag(item, "selected")));
            }
        }
        return new CalendarPage(calendars, text(root, "nextPageToken"));
    }

    private static RawEvent parseEvent(JsonNode item) {
        JsonNode start = item.get("start");
        JsonNode end = item.get("end");
        return new RawEvent(
                text(item, "id"),
                text(item, "summary"),
                text(item, "status"),
                text(item, "transparency"),
                text(item, "eventType"),
                declinedBySelf(item),
                text(start, "dateTime"),
                text(start, "date"),
                text(end, "dateTime"),
                text(end, "date"),
                text(start, "timeZone"));
    }

    /** True si el dueno del calendario rechazo la invitacion: no le ocupa tiempo. */
    private static boolean declinedBySelf(JsonNode item) {
        JsonNode attendees = item.get("attendees");
        if (attendees == null) {
            return false;
        }
        for (JsonNode attendee : attendees) {
            if (flag(attendee, "self") && "declined".equals(text(attendee, "responseStatus"))) {
                return true;
            }
        }
        return false;
    }

    private JsonNode getJson(URI uri, String accessToken, String forbiddenMessage) {
        String raw;
        try {
            raw = http.get()
                    .uri(uri)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode().value() == 403) {
                throw new ApiException(HttpStatus.CONFLICT, forbiddenMessage);
            }
            throw badGateway("No se pudo leer el calendario de Google");
        } catch (RuntimeException ex) {
            throw badGateway("No se pudo contactar a Google");
        }

        try {
            return objectMapper.readTree(raw);
        } catch (RuntimeException ex) {
            throw badGateway("Google respondio algo que no se pudo interpretar");
        }
    }

    private JsonNode postToken(MultiValueMap<String, String> form) {
        try {
            String raw = http.post()
                    .uri(TOKEN_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            return objectMapper.readTree(raw);
        } catch (HttpClientErrorException ex) {
            // 400 = codigo o refresh token invalido, expirado o revocado.
            // Otros 4xx (ej. 401 invalid_client) son un problema de configuracion nuestra.
            if (ex.getStatusCode().value() == 400) {
                throw new GoogleAuthException("Google rechazo la autorizacion");
            }
            throw badGateway("Google rechazo las credenciales del servidor");
        } catch (RuntimeException ex) {
            throw badGateway("No se pudo contactar a Google");
        }
    }

    private static String text(JsonNode parent, String field) {
        if (parent == null) {
            return null;
        }
        JsonNode value = parent.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static boolean flag(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        return value != null && value.asBoolean();
    }

    private static ApiException badGateway(String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, message);
    }
}