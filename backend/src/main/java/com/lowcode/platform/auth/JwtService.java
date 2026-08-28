package com.lowcode.platform.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Подписывает и проверяет ACCESS-токены (JWT) сами (не внешний IdP —
 * self-managed логин/пароль, см. обсуждение "Вариант A" при выборе способа
 * хранения учёток). Алгоритм — HS256 (симметричный ключ), этого достаточно,
 * пока backend один и тот же процесс и выпускает, и проверяет токены.
 *
 * С появлением refresh-токенов (RefreshTokenService) TTL здесь сознательно
 * короткий (дефолт — 15 минут, см. application.yml): именно refresh-токен, а
 * не access, теперь несёт "долгую" сессию (sliding 7 дней). Короткий access
 * ограничивает окно, в течение которого украденный (например, из логов
 * перехваченного трафика) токен вообще что-то даёт атакующему — JWT нельзя
 * отозвать досрочно (см. UserStatusCache про 30-секундный компромисс), так
 * что единственный рычаг — держать его TTL маленьким.
 */
@Component
public class JwtService {

    // Ревью CTO, п.1.6: без iss/aud токены, подписанные тем же секретом другим
    // сервисом (если он появится), были бы неотличимы от "своих". Фиксированные
    // константы, не конфиг — секрет всё равно один процесс на процесс, менять
    // их означало бы менять код, а не окружение.
    private static final String ISSUER = "lowcode-platform";
    private static final String AUDIENCE = "lowcode-platform-api";

    private final Key key;
    private final long expirationMs;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                       @Value("${app.jwt.expiration-ms:86400000}") long expirationMs) {
        // Keys.hmacShaKeyFor требует >= 256 бит (32 байта) — короче секрет
        // тут же упадёт при старте (WeakKeyException), а не тихо сработает
        // с ослабленной подписью.
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    public String generate(UUID userId, String email) {
        Instant now = Instant.now();
        return Jwts.builder()
                .setIssuer(ISSUER)
                .setAudience(AUDIENCE)
                .setSubject(userId.toString())
                .claim("email", email)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plusMillis(expirationMs)))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    /** Бросает io.jsonwebtoken.JwtException (или подклассы) на невалидный/просроченный/чужой токен. */
    public UUID parseUserId(String token) {
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(key)
                .requireIssuer(ISSUER)
                .requireAudience(AUDIENCE)
                .build()
                .parseClaimsJws(token)
                .getBody();
        return UUID.fromString(claims.getSubject());
    }
}
