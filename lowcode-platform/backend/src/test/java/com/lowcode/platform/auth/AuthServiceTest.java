package com.lowcode.platform.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Реальные BCryptPasswordEncoder, JwtService и RefreshTokenService (не моки) —
 * стоит вопрос теста именно в том, что пароль реально хешируется/сверяется и
 * оба токена реально выпускаются, а не просто что метод был вызван. Мокается
 * только персистентность (AppUserRepository, RefreshTokenRepository) — граница
 * с БД.
 *
 * Ротация/reuse-detection refresh-токена (id-цепочка replacedBy между
 * персистентными строками) здесь намеренно НЕ тестируется — с мокнутым
 * репозиторием сохраняемые сущности никогда не получают реальный
 * @GeneratedValue id (его назначает Hibernate только при настоящем персисте),
 * так что связывать их по id тут физически нечем. Эта часть проверяется в
 * integration/RefreshTokenFlowIntegrationTest (настоящий Postgres,
 * настоящие id) — тот же принцип, что и остальные Testcontainers-тесты в
 * проекте (см. их javadoc: "Mockito-юниты в принципе не видят...").
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private AppUserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final JwtService jwtService = new JwtService(
            "dev-only-insecure-secret-change-me-before-any-real-deployment", 900_000L);

    private AuthService service() {
        return new AuthService(userRepository, passwordEncoder, jwtService,
                new RefreshTokenService(refreshTokenRepository, 7));
    }

    @Test
    void register_newEmail_hashesPasswordAndIssuesAccessAndRefreshTokens() {
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);

        // save() не может просто вернуть inv.getArgument(0): это РЕАЛЬНЫЙ (не мок)
        // AppUser, у которого нет setId() — id всегда null, пока его не поставит
        // Hibernate при настоящем персисте. jwtService.generate(user.getId(), ...)
        // упал бы на NullPointerException (userId.toString() от null) — та же
        // ловушка, что уже была в ProjectServiceTest. Возвращаем из save() мок
        // с реальным id — имитация того, что отдаёт БД после персиста.
        UUID userId = UUID.randomUUID();
        AppUser savedUser = mock(AppUser.class);
        when(savedUser.getId()).thenReturn(userId);
        when(savedUser.getEmail()).thenReturn("alice@example.com");
        when(savedUser.getDisplayName()).thenReturn("Alice");
        when(userRepository.save(any(AppUser.class))).thenReturn(savedUser);

        AuthService.Session session = service()
                .register(new AuthDto.RegisterRequest("alice@example.com", "correct horse", "Alice"));
        AuthDto.TokenResponse response = session.tokenResponse();

        assertThat(response.email()).isEqualTo("alice@example.com");
        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.accessToken()).isNotBlank();
        assertThat(jwtService.parseUserId(response.accessToken())).isEqualTo(userId);

        assertThat(session.refreshToken()).isNotBlank();
        assertThat(session.refreshToken()).isNotEqualTo(response.accessToken());

        // Refresh-токен реально выдан (записан в БД через мокнутый репозиторий),
        // а не просто сгенерирован в воздух.
        ArgumentCaptor<RefreshToken> refreshCaptor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(refreshCaptor.capture());
        assertThat(refreshCaptor.getValue().getUserId()).isEqualTo(userId);
        assertThat(refreshCaptor.getValue().getTokenHash()).isNotEqualTo(session.refreshToken());

        // captor ловит АРГУМЕНТ, переданный в save() — реальный объект с уже
        // выставленным (настоящим) хешем пароля, отдельно от savedUser выше.
        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getPasswordHash()).isNotEqualTo("correct horse");
        assertThat(passwordEncoder.matches("correct horse", captor.getValue().getPasswordHash())).isTrue();
    }

    @Test
    void register_duplicateEmail_isRejected() {
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service()
                .register(new AuthDto.RegisterRequest("alice@example.com", "correct horse", "Alice")))
                .isInstanceOf(IllegalStateException.class);

        verify(userRepository, never()).save(any());
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void login_correctPassword_issuesAccessAndRefreshTokensForRealUserId() {
        AppUser user = mock(AppUser.class);
        UUID userId = UUID.randomUUID();
        when(user.getId()).thenReturn(userId);
        when(user.getEmail()).thenReturn("alice@example.com");
        when(user.getDisplayName()).thenReturn("Alice");
        when(user.getPasswordHash()).thenReturn(passwordEncoder.encode("correct horse"));
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));

        AuthService.Session session = service().login(new AuthDto.LoginRequest("alice@example.com", "correct horse"));

        assertThat(session.tokenResponse().userId()).isEqualTo(userId);
        assertThat(jwtService.parseUserId(session.tokenResponse().accessToken())).isEqualTo(userId);
        assertThat(session.refreshToken()).isNotBlank();
    }

    @Test
    void login_wrongPassword_throwsBadCredentials() {
        AppUser user = mock(AppUser.class);
        when(user.getPasswordHash()).thenReturn(passwordEncoder.encode("correct horse"));
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service().login(new AuthDto.LoginRequest("alice@example.com", "wrong password")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void login_unknownEmail_throwsSameBadCredentials_notLeakingWhetherEmailExists() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().login(new AuthDto.LoginRequest("ghost@example.com", "whatever")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void logout_delegatesToRefreshTokenServiceRevoke() {
        service().logout("some-raw-refresh-token");

        // revoke() сам по себе идемпотентен/тихий на неизвестный токен (см.
        // RefreshTokenServiceTest) — здесь проверяем только то, что AuthService
        // действительно СВЯЗАН с этим путём, а не глотает вызов молча сам.
        verify(refreshTokenRepository).findByTokenHash(any());
    }
}
