package com.group41.backend.schedule.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Cifrado AES-256-GCM para tokens guardados en la base de datos.
 *
 * GCM cifra y a la vez firma: si alguien altera el valor guardado, descifrar
 * falla en vez de devolver basura. Cada cifrado usa un IV aleatorio nuevo, asi
 * que el mismo token da textos distintos.
 */
@Component
public class TokenCrypto {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    /** @param base64Key clave de 32 bytes en Base64. Vacia = cifrado no disponible. */
    public TokenCrypto(@Value("${app.google.token-encryption-key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            this.key = null;
            return;
        }

        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("app.google.token-encryption-key no es Base64 valido", ex);
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalStateException(
                    "app.google.token-encryption-key debe ser de 32 bytes en Base64 (AES-256)");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public boolean isAvailable() {
        return key != null;
    }

    public String encrypt(String plainText) {
        requireKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            byte[] payload = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(cipherText, 0, payload, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("No se pudo cifrar el token", ex);
        }
    }

    public String decrypt(String payload) {
        requireKey();
        try {
            byte[] raw = Base64.getDecoder().decode(payload);
            if (raw.length <= IV_BYTES) {
                throw new IllegalStateException("Token cifrado invalido");
            }

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("No se pudo descifrar el token", ex);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException("El cifrado de tokens no esta configurado");
        }
    }
}