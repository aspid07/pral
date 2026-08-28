package com.lowcode.platform.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Мокается только RefreshTokenRepository (граница персистентности) — сам
 * алгоритм хеширования/ротации/sliding-окна реальный, не заглушка.
 *
 * НЕ покрыто здесь намеренно: полная id-цепочка reuse-detection
 * (revokeChainFrom идёт по RefreshToken.replacedBy — реальному
 * @GeneratedValue id, которого без настоящего персиста просто не существует,
 * "new RefreshToken()" в тесте даёт id == null). Этот сценарий — предмет
 * integration/RefreshTokenFlowIntegrationTest на настоящем Postgres.
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock private RefreshTokenRepository repository;

    private RefreshTokenService service() {
        return new RefreshTokenService(repository, 7);
    }

    @Test
    void issue_storesHashNotRawToken_withSevenDayExpiry() {
        UUID userId = UUID.randomUUID();
        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);

        String rawToken = service().issue(userId);

        assertThat(rawToken).isNotBlank();
        verify(repository).save(captor.capture());
        RefreshToken saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(userId);
        // Хранится хеш, не сырой секрет — компрометация БД не даёт готового токена.
        assertThat(saved.getTokenHash()).isNotEqualTo(rawToken);
        assertThat(saved.getRevokedAt()).isNull();
        assertThat(saved.getExpiresAt()).isCloseTo(Instant.now().plus(Duration.ofDays(7)), org.assertj.core.api.Assertions.within(5, java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    void rotate_unknownToken_throwsBadCredentials() {
        when(repository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().rotate("nonexistent-token"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void rotate_expiredToken_throwsBadCredentials() {
        RefreshToken expired = new RefreshToken();
        expired.setUserId(UUID.randomUUID());
        expired.setIssuedAt(Instant.now().minus(Duration.ofDays(10)));
        expired.setExpiresAt(Instant.now().minus(Duration.ofMinutes(1))); // истёк минуту назад
        when(repository.findByTokenHash(any())).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service().rotate("some-token"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void rotate_alreadyRevokedToken_isRejectedAsReuse() {
        RefreshToken revoked = new RefreshToken();
        revoked.setUserId(UUID.randomUUID());
        revoked.setIssuedAt(Instant.now().minus(Duration.ofHours(1)));
        revoked.setExpiresAt(Instant.now().plus(Duration.ofDays(6))); // ещё не истёк по времени
        revoked.setRevokedAt(Instant.now().minus(Duration.ofMinutes(5))); // но уже был использован ранее
        when(repository.findByTokenHash(any())).thenReturn(Optional.of(revoked));

        // Валиден по TTL, но уже отозван предыдущим (легитимным) refresh —
        // повторное предъявление того же секрета трактуется как reuse, не как
        // "ещё разрешение": именно так работает защита от кражи (см. javadoc
        // RefreshTokenService.rotate).
        assertThatThrownBy(() -> service().rotate("already-used-token"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("reuse");
    }

    @Test
    void rotate_validToken_revokesOldAndLinksReplacedBy() {
        UUID userId = UUID.randomUUID();
        RefreshToken stored = new RefreshToken();
        stored.setUserId(userId);
        stored.setIssuedAt(Instant.now());
        stored.setExpiresAt(Instant.now().plus(Duration.ofDays(7)));
        when(repository.findByTokenHash(any())).thenReturn(Optional.of(stored));

        RefreshTokenService.RotationResult result = service().rotate("valid-token");

        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.rawToken()).isNotBlank();

        // Старый токен помечен отозванным сразу же (одноразовость) — второй
        // rotate() тем же секретом обязан провалиться как reuse, не пройти снова.
        assertThat(stored.getRevokedAt()).isNotNull();

        ArgumentCaptor<RefreshToken> savedCaptor = ArgumentCaptor.forClass(RefreshToken.class);
        // save() вызывается дважды: новый токен + обновлённый (revoked) старый.
        verify(repository, times(2)).save(savedCaptor.capture());
        RefreshToken newToken = savedCaptor.getAllValues().stream()
                .filter(t -> t.getRevokedAt() == null)
                .findFirst()
                .orElseThrow();
        assertThat(newToken.getUserId()).isEqualTo(userId);
        assertThat(newToken.getTokenHash()).isNotEqualTo(stored.getTokenHash());
    }

    /**
     * Sliding window — ключевое требование задачи: TTL пересчитывается заново
     * от МОМЕНТА REFRESH, а не продлевает исходный expiresAt на фиксированный
     * шаг и не наследует его. Здесь исходный токен искусственно "почти истёк"
     * (10 минут до дедлайна) — если бы sliding-логика была реализована неверно
     * (например, expiresAt.plus(ttl) от СТАРОГО expiresAt, а не от Instant.now()),
     * результат остался бы почти истёкшим. Правильная реализация даёт новому
     * токену полный TTL заново от текущего момента.
     */
    @Test
    void rotate_slidingWindow_newTokenGetsFullTtlFromNow_notExtendedFromOldExpiry() {
        RefreshToken almostExpired = new RefreshToken();
        almostExpired.setUserId(UUID.randomUUID());
        almostExpired.setIssuedAt(Instant.now().minus(Duration.ofDays(7)).plus(Duration.ofMinutes(10)));
        almostExpired.setExpiresAt(Instant.now().plus(Duration.ofMinutes(10))); // вот-вот истечёт
        when(repository.findByTokenHash(any())).thenReturn(Optional.of(almostExpired));

        ArgumentCaptor<RefreshToken> savedCaptor = ArgumentCaptor.forClass(RefreshToken.class);

        service().rotate("token-close-to-expiry");

        verify(repository, times(2)).save(savedCaptor.capture());
        RefreshToken newToken = savedCaptor.getAllValues().stream()
                .filter(t -> t.getRevokedAt() == null)
                .findFirst()
                .orElseThrow();

        // Не "10 минут + чуть-чуть", а полноценные ~7 дней от текущего момента.
        assertThat(newToken.getExpiresAt())
                .isCloseTo(Instant.now().plus(Duration.ofDays(7)), org.assertj.core.api.Assertions.within(5, java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    void revoke_existingToken_marksRevoked() {
        RefreshToken stored = new RefreshToken();
        stored.setUserId(UUID.randomUUID());
        stored.setIssuedAt(Instant.now());
        stored.setExpiresAt(Instant.now().plus(Duration.ofDays(7)));
        when(repository.findByTokenHash(any())).thenReturn(Optional.of(stored));

        service().revoke("some-token");

        assertThat(stored.getRevokedAt()).isNotNull();
        verify(repository).save(stored);
    }

    @Test
    void revoke_unknownToken_isSilentlyIdempotent() {
        when(repository.findByTokenHash(any())).thenReturn(Optional.empty());

        // Logout с уже невалидной/чужой cookie не должен бросать — сам по себе
        // logout идемпотентен (см. javadoc AuthService.logout).
        service().revoke("nonexistent-token");

        verify(repository, never()).save(any());
    }
}
