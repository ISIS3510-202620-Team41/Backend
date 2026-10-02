package com.group41.backend;

import com.group41.backend.schedule.service.TokenCrypto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Cifrado de tokens (AES-256-GCM)")
class TokenCryptoTests {

    private static String validKey() {
        return Base64.getEncoder().encodeToString(new byte[32]);
    }

    @Test
    @DisplayName("lo cifrado se descifra al valor original")
    void roundTrip() {
        TokenCrypto crypto = new TokenCrypto(validKey());

        String encrypted = crypto.encrypt("1//0gRefreshTokenDeEjemplo");

        assertThat(crypto.decrypt(encrypted)).isEqualTo("1//0gRefreshTokenDeEjemplo");
    }

    @Test
    @DisplayName("no deja el token en claro y cada cifrado da un resultado distinto")
    void ciphertextHidesTokenAndIsRandomized() {
        TokenCrypto crypto = new TokenCrypto(validKey());

        String first = crypto.encrypt("secreto");
        String second = crypto.encrypt("secreto");

        assertThat(first).doesNotContain("secreto").isNotEqualTo(second);
    }

    @Test
    @DisplayName("un valor alterado no se descifra")
    void tamperedValueIsRejected() {
        TokenCrypto crypto = new TokenCrypto(validKey());
        byte[] raw = Base64.getDecoder().decode(crypto.encrypt("secreto"));
        raw[raw.length - 1] ^= 1;
        String tampered = Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> crypto.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("una clave que no mide 32 bytes se rechaza al arrancar")
    void wrongKeyLengthIsRejected() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new TokenCrypto(shortKey)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("sin clave el cifrado no esta disponible y no cifra")
    void blankKeyMeansUnavailable() {
        TokenCrypto crypto = new TokenCrypto("");

        assertThat(crypto.isAvailable()).isFalse();
        assertThatThrownBy(() -> crypto.encrypt("x")).isInstanceOf(IllegalStateException.class);
    }
}
