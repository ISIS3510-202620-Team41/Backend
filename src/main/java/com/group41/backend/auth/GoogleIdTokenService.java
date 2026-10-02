package com.group41.backend.auth;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.group41.backend.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

@Service
public class GoogleIdTokenService {

    private final GoogleIdTokenVerifier verifier;

    public GoogleIdTokenService(@Value("${app.google.client-id:}") String clientId) {
        if (clientId == null || clientId.isBlank()) {
            this.verifier = null;
            return;
        }

        try {
            this.verifier = new GoogleIdTokenVerifier.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance())
                    .setAudience(List.of(clientId))
                    .build();
        } catch (GeneralSecurityException | IOException ex) {
            throw new IllegalStateException("No se pudo inicializar el verificador de Google", ex);
        }
    }

    public GoogleIdentity verify(String idToken) {
        if (verifier == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "La autenticacion con Google no esta configurada");
        }

        try {
            GoogleIdToken token = verifier.verify(idToken);
            if (token == null) {
                throw invalidToken();
            }

            GoogleIdToken.Payload payload = token.getPayload();
            if (payload.getEmail() == null
                    || payload.getEmail().isBlank()
                    || !Boolean.TRUE.equals(payload.getEmailVerified())
                    || payload.getSubject() == null
                    || payload.getSubject().isBlank()) {
                throw invalidToken();
            }

            return new GoogleIdentity(
                    payload.getSubject(),
                    payload.getEmail(),
                    payload.get("name") instanceof String name ? name : null
            );
        } catch (GeneralSecurityException | IOException | IllegalArgumentException ex) {
            throw invalidToken();
        }
    }

    private static ApiException invalidToken() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "Token de Google invalido o expirado");
    }

    public record GoogleIdentity(String subject, String email, String name) {
    }
}
