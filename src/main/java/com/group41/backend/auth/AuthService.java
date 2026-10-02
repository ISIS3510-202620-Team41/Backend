package com.group41.backend.auth;

import com.group41.backend.common.ApiException;
import com.group41.backend.security.JwtService;
import com.group41.backend.user.domain.User;
import com.group41.backend.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final LoginRateLimiter rateLimiter;
    private final GoogleIdTokenService googleIdTokenService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       RefreshTokenService refreshTokenService,
                       LoginRateLimiter rateLimiter,
                       GoogleIdTokenService googleIdTokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.rateLimiter = rateLimiter;
        this.googleIdTokenService = googleIdTokenService;
    }

    @Transactional
    public AuthDtos.AuthResponse loginWithGoogle(AuthDtos.GoogleLoginRequest request) {
        GoogleIdTokenService.GoogleIdentity identity =
                googleIdTokenService.verify(request.idToken());
        String email = normalizeEmail(identity.email());

        User user = userRepository.findByGoogleSubject(identity.subject())
                .orElseGet(() -> linkOrCreateGoogleUser(identity, email));

        return buildResponse(user);
    }

    @Transactional
    public AuthDtos.AuthResponse register(AuthDtos.RegisterRequest request) {
        String email = normalizeEmail(request.email());

        if (userRepository.existsByEmail(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "Ese correo ya esta registrado");
        }

        User user = new User(
                email,
                passwordEncoder.encode(request.password()),
                request.name().trim()
        );
        userRepository.save(user);

        return buildResponse(user);
    }

    @Transactional
    public AuthDtos.AuthResponse login(AuthDtos.LoginRequest request, String clientIp) {
        String email = normalizeEmail(request.email());

        // Se consulta antes de tocar la base de datos: si ya hubo demasiados
        // intentos fallidos, ni siquiera vale la pena verificar la contrasena.
        if (!rateLimiter.tryConsume(email, clientIp)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Demasiados intentos fallidos. Espera unos minutos");
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(AuthService::invalidCredentials);

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw invalidCredentials();
        }

        // Solo un login correcto limpia el contador. Asi una rafaga de intentos
        // fallidos sigue penalizada aunque al final acierten.
        rateLimiter.reset(email, clientIp);

        return buildResponse(user);
    }

    /** Canjea un refresh token por un par nuevo, revocando el anterior. */
    @Transactional
    public AuthDtos.AuthResponse refresh(AuthDtos.RefreshRequest request) {
        RefreshTokenService.RotationResult result = refreshTokenService.rotate(request.refreshToken());

        return new AuthDtos.AuthResponse(
                jwtService.generateToken(result.user().getId()),
                result.refreshToken(),
                jwtService.getExpirationSeconds(),
                toUserResponse(result.user())
        );
    }

    /**
     * Cierra la sesion de este dispositivo revocando su refresh token.
     *
     * Ojo: el access token que el cliente ya tenga sigue siendo valido hasta
     * que expire (15 minutos). Es la contrapartida de usar JWT sin estado.
     */
    @Transactional
    public void logout(AuthDtos.RefreshRequest request) {
        refreshTokenService.revoke(request.refreshToken());
    }

    private AuthDtos.AuthResponse buildResponse(User user) {
        return new AuthDtos.AuthResponse(
                jwtService.generateToken(user.getId()),
                refreshTokenService.issue(user),
                jwtService.getExpirationSeconds(),
                toUserResponse(user)
        );
    }

    private User linkOrCreateGoogleUser(GoogleIdTokenService.GoogleIdentity identity,
                                        String email) {
        User user = userRepository.findByEmail(email).orElse(null);
        if (user != null) {
            if (user.getGoogleSubject() != null
                    && !user.getGoogleSubject().equals(identity.subject())) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Ese correo ya esta vinculado a otra cuenta de Google");
            }
            user.setGoogleSubject(identity.subject());
            return userRepository.save(user);
        }

        String name = identity.name() == null || identity.name().isBlank()
                ? email.substring(0, email.indexOf('@'))
                : identity.name().trim();
        User googleUser = new User(
                email,
                passwordEncoder.encode(UUID.randomUUID().toString()),
                name,
                identity.subject()
        );
        return userRepository.save(googleUser);
    }

    public static AuthDtos.UserResponse toUserResponse(User user) {
        return new AuthDtos.UserResponse(
                user.getId().toString(),
                user.getEmail(),
                user.getName(),
                user.getBio(),
                user.getAvatarUrl()
        );
    }

    /**
     * Mismo mensaje para "ese correo no existe" y "la contrasena no coincide".
     *
     * Si distinguieramos los dos casos, cualquiera podria averiguar que correos
     * estan registrados probandolos uno por uno. Eso es enumeracion de usuarios
     * y es una fuga de privacidad real.
     */
    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "Credenciales invalidas");
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
