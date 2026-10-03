package com.group41.backend.user.controller;

import com.group41.backend.auth.AuthDtos;
import com.group41.backend.user.domain.User;
import com.group41.backend.user.service.FriendshipService;
import com.group41.backend.analytics.AnalyticsPublisher;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/friends")
public class FriendshipController {

    private final FriendshipService friendshipService;
    private final AnalyticsPublisher analytics;

    public FriendshipController(FriendshipService friendshipService, AnalyticsPublisher analytics) {
        this.friendshipService = friendshipService;
        this.analytics = analytics;
    }

    /** Envia una solicitud de amistad por correo. */
    @PostMapping("/requests")
    public ResponseEntity<UserDtos.FriendRequestResponse> sendRequest(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody UserDtos.SendFriendRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(friendshipService.sendRequest(user, request.addresseeEmail()));
    }

    /** Solicitudes entrantes que esperan respuesta. */
    @GetMapping("/requests/pending")
    public ResponseEntity<List<UserDtos.FriendRequestResponse>> pending(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(friendshipService.pendingRequests(user));
    }

    /** Acepta o rechaza una solicitud que recibi. */
    @PatchMapping("/requests/{id}")
    public ResponseEntity<UserDtos.FriendRequestResponse> respond(
            @AuthenticationPrincipal User user,
            @PathVariable UUID id,
            @Valid @RequestBody UserDtos.RespondFriendRequest request) {
        return ResponseEntity.ok(friendshipService.respond(user, id, request.accept()));
    }

    /** Lista de amigos aceptados. */
    @GetMapping
    public ResponseEntity<List<AuthDtos.UserResponse>> friends(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(friendshipService.listFriends(user));
    }

    /** Amigos libres en un instante dado, ej. ?dateTime=2026-10-02T15:00:00Z. */
    @GetMapping("/gaps")
    public ResponseEntity<List<AuthDtos.UserResponse>> gaps(
            @AuthenticationPrincipal User user,
            @RequestParam("dateTime") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime dateTime) {
        List<AuthDtos.UserResponse> free = friendshipService.availableFriends(user, dateTime.toZonedDateTime());
        // Aun no existe el concepto de "plan": por ahora solo se emite "viewed".
        analytics.friendAvailabilityUsed(user.getId(), "viewed");
        return ResponseEntity.ok(free);
    }
}