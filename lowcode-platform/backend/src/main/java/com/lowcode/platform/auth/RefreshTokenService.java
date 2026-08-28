package com.lowcode.platform.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Refresh-токен — непрозрачный случайный секрет (256 бит из SecureRandom),
 * не JWT: в отличие от access-токена его нужно уметь ОТОЗВАТЬ до истечения
 * TTL (logout, обнаружение кражи), а самодостаточный подписанный JWT отозвать
 * нельзя без отдельного blacklist'а — то есть без БД всё равно не обойтись,
 * так что проще сразу сделать refresh-токен ссылкой на строку в БД.
 *
 * В БД хранится SHA-256 хеш, не сырое значение (та же логика, что и
 * password_hash в AppUser) — компрометация БД/дампа/лога сама по себе не
 * даёт готового к использованию секрета. BCrypt здесь намеренно НЕ
 * используется: в отличие от пароля пользователя, у refresh-токена и так
 * 256 бит энтропии из SecureRandom — соль и искусственная дороговизна BCrypt
 * защищают от подбора низкоэнтропийного секрета (пароля), здесь подбирать
 * нечего, а BCrypt.matches на каждый refresh — заметная и бесполезная
 * нагрузка на CPU при том, что происходит чаще логина.
 *
 * Sliding window (по заданию — 7 дней): {@link #rotate} пересчитывает
 * expiresAt заново от текущего момента при КАЖДОМ успешном refresh, а не
 * фиксирует его один раз при первом login. Сессия обрывается, только если
 * токеном не пользовались все 7 дней подряд — при регулярном использовании
 * продукта живёт неделями/месяцами без повторного логина. Это отличается от
 * absolute-модели (жёсткий потолок ровно 7 дней от входа вне зависимости от
 * активности) — здесь сознательно выбран sliding, как явно указано в задаче.
 *
 * Ротация: токен одноразовый — предъявленный refresh-токен немедленно
 * помечается revoked и получает ссылку (replacedBy) на токен, который пришёл
 * ему на смену, клиенту выдаётся новый. Это даёт возможность обнаружить
 * кражу секрета: если уже отозванный (то есть уже использованный) токен
 * предъявят снова, см. reuse-detection в {@link #rotate}.
 */
@Service
public class RefreshTokenService {

    private static final int TOKEN_BYTES = 32; // 256 бит энтропии
    private static final String HASH_ALGORITHM = "SHA-256";

    private final RefreshTokenRepository repository;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Duration ttl;

    public RefreshTokenService(RefreshTokenRepository repository,
                                @Value("${app.refresh-token.ttl-days:7}") long ttlDays) {
        this.repository = repository;
        this.ttl = Duration.ofDays(ttlDays);
    }

    public Duration ttl() {
        return ttl;
    }

    /** Выдаёт первый refresh-токен сессии (register/login). */
    @Transactional
    public String issue(UUID userId) {
        String rawToken = randomToken();
        RefreshToken entity = new RefreshToken();
        entity.setUserId(userId);
        entity.setTokenHash(hash(rawToken));
        Instant now = Instant.now();
        entity.setIssuedAt(now);
        entity.setExpiresAt(now.plus(ttl));
        repository.save(entity);
        return rawToken;
    }

    /**
     * Проверяет предъявленный refresh-токен и, если он валиден, выдаёт новый
     * взамен (ротация) с продлённым sliding-окном. Бросает
     * {@link BadCredentialsException} на невалидный/просроченный/отозванный
     * токен — вызывающий код (AuthController) превращает это в 401, как и
     * остальные auth-ошибки в проекте (см. ApiExceptionHandler).
     *
     * Reuse-detection: если предъявленный токен уже был отозван РАНЬШЕ (то
     * есть кто-то уже выполнил refresh этим секретом до текущего запроса),
     * это с высокой вероятностью значит, что секрет скомпрометирован —
     * либо украден и уже использован атакующим, либо (реже) сетевой ретрай
     * задвоил запрос легитимного клиента. В обоих случаях безопасный ответ
     * один: отозвать всю цепочку токенов, порождённых ОТ скомпрометированного
     * (см. {@link #revokeChainFrom}), а не только этот конкретный — иначе
     * атакующий с уже полученным при первом (успешном) использовании новым
     * токеном продолжил бы ротировать сессию параллельно с легитимным
     * пользователем, каждый со своим действующим токеном, бесконечно.
     */
    @Transactional
    public RotationResult rotate(String rawToken) {
        RefreshToken stored = repository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));

        if (stored.getRevokedAt() != null) {
            revokeChainFrom(stored);
            throw new BadCredentialsException("Refresh token reuse detected, session revoked");
        }
        if (stored.getExpiresAt().isBefore(Instant.now())) {
            throw new BadCredentialsException("Refresh token expired");
        }

        Instant now = Instant.now();
        String newRawToken = randomToken();
        RefreshToken next = new RefreshToken();
        next.setUserId(stored.getUserId());
        next.setTokenHash(hash(newRawToken));
        next.setIssuedAt(now);
        next.setExpiresAt(now.plus(ttl)); // sliding: TTL заново от момента refresh, не от issuedAt исходного токена
        repository.save(next);

        stored.setRevokedAt(now);
        stored.setReplacedBy(next.getId());
        repository.save(stored);

        return new RotationResult(stored.getUserId(), newRawToken);
    }

    /** Logout: отзывает конкретный токен. Тихо игнорирует отсутствующий/уже отозванный — logout идемпотентен. */
    @Transactional
    public void revoke(String rawToken) {
        repository.findByTokenHash(hash(rawToken)).ifPresent(stored -> {
            if (stored.getRevokedAt() == null) {
                stored.setRevokedAt(Instant.now());
                repository.save(stored);
            }
        });
    }

    private void revokeChainFrom(RefreshToken compromised) {
        Instant now = Instant.now();
        UUID nextId = compromised.getReplacedBy();
        while (nextId != null) {
            RefreshToken next = repository.findById(nextId).orElse(null);
            if (next == null || next.getRevokedAt() != null) {
                break; // конец цепочки либо уже отозвано более ранним вызовом — не пересекать дважды
            }
            next.setRevokedAt(now);
            repository.save(next);
            nextId = next.getReplacedBy();
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 гарантированно есть в любой JVM (стандартный алгоритм из
            // JCA) — если его вдруг нет, это фатальная проблема окружения, а
            // не ожидаемый рабочий путь, поэтому unchecked, а не проброс checked.
            throw new IllegalStateException(HASH_ALGORITHM + " not available", e);
        }
    }

    public record RotationResult(UUID userId, String rawToken) {}
}
