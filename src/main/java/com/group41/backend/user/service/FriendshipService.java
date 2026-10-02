package com.group41.backend.user.service;

import com.group41.backend.schedule.service.ScheduleService;
import com.group41.backend.auth.AuthDtos;
import com.group41.backend.auth.AuthService;
import com.group41.backend.common.ApiException;
import com.group41.backend.user.controller.UserDtos;
import com.group41.backend.user.domain.Friendship;
import com.group41.backend.user.domain.FriendshipStatus;
import com.group41.backend.user.domain.User;
import com.group41.backend.user.repository.FriendshipRepository;
import com.group41.backend.user.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.Set;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class FriendshipService {

    private final FriendshipRepository friendshipRepository;
    private final UserRepository userRepository;
    private final ScheduleService scheduleService;

    public FriendshipService(FriendshipRepository friendshipRepository,
                             UserRepository userRepository,
                             ScheduleService scheduleService) {
        this.friendshipRepository = friendshipRepository;
        this.userRepository = userRepository;
        this.scheduleService = scheduleService;
    }

    @Transactional
    public UserDtos.FriendRequestResponse sendRequest(User me, String addresseeEmail) {
        String email = addresseeEmail.trim().toLowerCase(Locale.ROOT);

        User addressee = userRepository.findByEmail(email)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "No existe un usuario con ese correo"));

        if (addressee.getId().equals(me.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "No puedes enviarte una solicitud a ti mismo");
        }

        // La restriccion unica solo cubre (A -> B). Esta consulta mira ambas
        // direcciones para que tampoco pueda existir (B -> A).
        Optional<Friendship> existing = friendshipRepository.findBetween(me.getId(), addressee.getId());
        if (existing.isPresent()) {
            Friendship friendship = existing.get();
            boolean iSentIt = friendship.getRequesterId().equals(me.getId());

            switch (friendship.getStatus()) {
                case ACCEPTED -> throw new ApiException(HttpStatus.CONFLICT, "Ya son amigos");
                case PENDING -> throw new ApiException(HttpStatus.CONFLICT, iSentIt
                        ? "Ya enviaste una solicitud a esta persona"
                        : "Esa persona ya te envio una solicitud: revisa tus pendientes");
                case REJECTED -> {
                    // Quien fue rechazado no puede insistir. Quien rechazo si puede
                    // cambiar de opinion y escribirle: se reemplaza la fila vieja.
                    if (iSentIt) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "No es posible enviar la solicitud a este usuario");
                    }
                    friendshipRepository.delete(friendship);
                }
            }
        }

        Friendship created;
        try {
            created = friendshipRepository.saveAndFlush(new Friendship(me.getId(), addressee.getId()));
        } catch (DataIntegrityViolationException ex) {
            // Dos peticiones simultaneas chocando con la restriccion unica.
            throw new ApiException(HttpStatus.CONFLICT, "Ya existe una solicitud con esta persona");
        }

        return toResponse(created, addressee);
    }

    /** Solicitudes entrantes pendientes, las mas recientes primero. */
    @Transactional(readOnly = true)
    public List<UserDtos.FriendRequestResponse> pendingRequests(User me) {
        List<Friendship> pending = friendshipRepository
                .findByAddresseeIdAndStatusOrderByCreatedAtDesc(me.getId(), FriendshipStatus.PENDING);

        Map<UUID, User> senders = loadUsers(pending.stream().map(Friendship::getRequesterId).toList());

        return pending.stream()
                .filter(f -> senders.containsKey(f.getRequesterId()))
                .map(f -> toResponse(f, senders.get(f.getRequesterId())))
                .toList();
    }

    @Transactional
    public UserDtos.FriendRequestResponse respond(User me, UUID requestId, boolean accept) {
        // Si la solicitud no existe o no va dirigida a mi, la respuesta es la
        // misma (404): asi nadie puede averiguar que ids pertenecen a otros.
        Friendship friendship = friendshipRepository.findById(requestId)
                .filter(f -> f.getAddresseeId().equals(me.getId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Solicitud no encontrada"));

        if (friendship.getStatus() != FriendshipStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "Esa solicitud ya fue respondida");
        }

        if (accept) {
            friendship.accept();
        } else {
            friendship.reject();
        }
        friendshipRepository.save(friendship);

        User requester = userRepository.findById(friendship.getRequesterId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Solicitud no encontrada"));

        return toResponse(friendship, requester);
    }

    /** Amigos aceptados, ordenados por nombre. */
    @Transactional(readOnly = true)
    public List<AuthDtos.UserResponse> listFriends(User me) {
        List<UUID> ids = friendIds(me.getId());
        Map<UUID, User> users = loadUsers(ids);

        return ids.stream()
                .map(users::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(User::getName, String.CASE_INSENSITIVE_ORDER))
                .map(AuthService::toUserResponse)
                .toList();
    }

    /** Ids de los amigos aceptados. Lo reutiliza el modulo de horarios para los huecos. */
    @Transactional(readOnly = true)
    public List<UUID> friendIds(UUID userId) {
        return friendshipRepository.findAcceptedFor(userId).stream()
                .map(f -> f.otherParty(userId))
                .toList();
    }

    /** Amigos aceptados que NO tienen un bloque de tiempo activo en ese instante. */
    @Transactional(readOnly = true)
    public List<AuthDtos.UserResponse> availableFriends(User me, ZonedDateTime at) {
        List<UUID> ids = friendIds(me.getId());
        if (ids.isEmpty()) {
            return List.of();
        }

        Set<UUID> busy = scheduleService.busyUserIds(ids, at);
        List<UUID> freeIds = ids.stream().filter(id -> !busy.contains(id)).toList();

        return loadUsers(freeIds).values().stream()
                .sorted(Comparator.comparing(User::getName, String.CASE_INSENSITIVE_ORDER))
                .map(AuthService::toUserResponse)
                .toList();
    }

    private Map<UUID, User> loadUsers(Collection<UUID> ids) {
        return userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private static UserDtos.FriendRequestResponse toResponse(Friendship friendship, User otherParty) {
        return new UserDtos.FriendRequestResponse(
                friendship.getId().toString(),
                friendship.getStatus().name(),
                AuthService.toUserResponse(otherParty),
                friendship.getCreatedAt()
        );
    }
}