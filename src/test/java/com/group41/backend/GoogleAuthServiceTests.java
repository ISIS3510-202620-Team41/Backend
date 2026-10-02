package com.group41.backend;

import com.group41.backend.auth.AuthDtos;
import com.group41.backend.auth.AuthService;
import com.group41.backend.auth.GoogleIdTokenService;
import com.group41.backend.auth.LoginRateLimiter;
import com.group41.backend.auth.RefreshTokenService;
import com.group41.backend.security.JwtService;
import com.group41.backend.user.domain.User;
import com.group41.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoogleAuthServiceTests {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;
    @Mock
    private RefreshTokenService refreshTokenService;
    @Mock
    private LoginRateLimiter rateLimiter;
    @Mock
    private GoogleIdTokenService googleIdTokenService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userRepository,
                passwordEncoder,
                jwtService,
                refreshTokenService,
                rateLimiter,
                googleIdTokenService);
        when(jwtService.generateToken(any())).thenReturn("access");
        when(jwtService.getExpirationSeconds()).thenReturn(900L);
        when(refreshTokenService.issue(any())).thenReturn("refresh");
    }

    @Test
    void returnsExistingUserByGoogleSubject() {
        User user = user("existing@example.com", "Existing");
        when(googleIdTokenService.verify("token"))
                .thenReturn(new GoogleIdTokenService.GoogleIdentity(
                        "google-sub", "existing@example.com", "Google Name"));
        when(userRepository.findByGoogleSubject("google-sub")).thenReturn(Optional.of(user));

        AuthDtos.AuthResponse response = authService.loginWithGoogle(
                new AuthDtos.GoogleLoginRequest("token"));

        assertThat(response.user().email()).isEqualTo("existing@example.com");
        verify(userRepository).findByGoogleSubject("google-sub");
    }

    @Test
    void linksExistingEmailAndCreatesNewUserWhenNeeded() {
        User user = user("local@example.com", "Local");
        when(googleIdTokenService.verify("token"))
                .thenReturn(new GoogleIdTokenService.GoogleIdentity(
                        "google-sub", "LOCAL@example.com", "Google Name"));
        when(userRepository.findByGoogleSubject("google-sub")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("local@example.com")).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        authService.loginWithGoogle(new AuthDtos.GoogleLoginRequest("token"));

        assertThat(user.getGoogleSubject()).isEqualTo("google-sub");
        verify(userRepository).save(user);
    }

    private static User user(String email, String name) {
        User user = new User(email, "hash", name);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }
}
