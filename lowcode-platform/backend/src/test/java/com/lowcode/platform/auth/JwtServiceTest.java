package com.lowcode.platform.auth;

import io.jsonwebtoken.security.WeakKeyException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Настоящий JwtService (не мок) — тут важна реальная работа jjwt, а не факт
 * вызова метода. Секрет длиной ровно как в dev-дефолте application.yml (61 байт).
 */
class JwtServiceTest {

    private static final String SECRET = "dev-only-insecure-secret-change-me-before-any-real-deployment";

    private JwtService service() {
        return new JwtService(SECRET, 86_400_000L);
    }

    @Test
    void generate_thenParse_roundTripsTheSameUserId() {
        UUID userId = UUID.randomUUID();

        String token = service().generate(userId, "alice@example.com");
        UUID parsed = service().parseUserId(token);

        assertThat(parsed).isEqualTo(userId);
    }

    @Test
    void parseUserId_tamperedToken_throws() {
        UUID userId = UUID.randomUUID();
        String token = service().generate(userId, "alice@example.com");
        // Портим последний символ подписи — должно провалить проверку HMAC.
        String tampered = token.substring(0, token.length() - 1) + (token.endsWith("a") ? "b" : "a");

        assertThatThrownBy(() -> service().parseUserId(tampered)).isInstanceOf(io.jsonwebtoken.JwtException.class);
    }

    @Test
    void constructor_secretShorterThan32Bytes_throwsWeakKeyException() {
        assertThatThrownBy(() -> new JwtService("too-short-secret", 86_400_000L))
                .isInstanceOf(WeakKeyException.class);
    }

    @Test
    void parseUserId_tokenSignedWithDifferentSecret_isRejected() {
        UUID userId = UUID.randomUUID();
        JwtService signedByOther = new JwtService(
                "a-completely-different-secret-that-is-also-long-enough-32b", 86_400_000L);
        String token = signedByOther.generate(userId, "alice@example.com");

        assertThatThrownBy(() -> service().parseUserId(token)).isInstanceOf(io.jsonwebtoken.JwtException.class);
    }
}
