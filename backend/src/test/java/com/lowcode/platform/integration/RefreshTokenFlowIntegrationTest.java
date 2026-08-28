package com.lowcode.platform.integration;

import com.lowcode.platform.auth.AuthDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Настоящий Postgres + настоящие Flyway-миграции (та же причина, что и у
 * BlockAndEntryPointDeleteCascadeIntegrationTest) — ротация refresh-токенов
 * (RefreshTokenService.rotate/revokeChainFrom) связывает строки по РЕАЛЬНОМУ
 * @GeneratedValue id (replaced_by), которого у объекта, ни разу не прошедшего
 * через настоящий persist, попросту не существует (id == null). Юнит-тест на
 * моках (RefreshTokenServiceTest) поэтому сознательно не проверяет
 * многошаговую reuse-цепочку целиком — только этот тест реально её гоняет.
 *
 * Требует Docker в окружении, где выполняется `./gradlew test`.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RefreshTokenFlowIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private TestRestTemplate rest;

    /**
     * TestRestTemplate по умолчанию — на SimpleClientHttpRequestFactory (JDK
     * HttpURLConnection): POST-запрос, получивший 401 (именно этот сценарий
     * тут кругом — reuse-detection, logout, отсутствующая cookie), кидает
     * HttpRetryException вместо того, чтобы просто отдать ResponseEntity со
     * статусом — JDK пытается провести retry аутентификации и не может
     * сделать это в streaming-режиме, раз тело запроса уже отправлено.
     * Apache HttpClient 5 этой проблемы не имеет.
     */
    @BeforeEach
    void useApacheHttpClient() {
        rest.getRestTemplate().setRequestFactory(new HttpComponentsClientHttpRequestFactory());
    }

    /**
     * Ротация: refresh валидной cookie выдаёт новый access и НОВУЮ
     * refresh-cookie; старая cookie одноразовая и повторно не годится
     * (reuse-detection). Второй refresh валидной (новой) cookie тоже
     * успевает пройти — то есть цепочка живёт, только конкретный уже
     * использованный секрет становится негодным.
     */
    @Test
    void refresh_rotatesRefreshCookie_oldOneNoLongerWorks() {
        Session session = registerAndCaptureSession();

        // Первая ротация — валидной cookie из логина.
        ResponseEntity<AuthDto.TokenResponse> firstRefresh = postWithCookie("/api/v1/auth/refresh", session.refreshCookie);
        assertThat(firstRefresh.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(firstRefresh.getBody()).isNotNull();
        assertThat(firstRefresh.getBody().accessToken()).isNotEqualTo(session.accessToken);
        String secondGenCookie = extractCookieValue(firstRefresh);
        assertThat(secondGenCookie).isNotEqualTo(session.refreshCookie);

        // Повторное предъявление УЖЕ ИСПОЛЬЗОВАННОЙ (первого поколения) cookie —
        // reuse, должно быть отклонено, а не тихо выдать ещё один access.
        ResponseEntity<AuthDto.TokenResponse> reuseAttempt = postWithCookie("/api/v1/auth/refresh", session.refreshCookie);
        assertThat(reuseAttempt.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Легитимная (второго поколения) cookie, полученная от первого refresh,
        // при этом ЕЩЁ действует — сама по себе ротация не единственный источник
        // одноразовости другого токена в цепочке.
        ResponseEntity<AuthDto.TokenResponse> secondRefresh = postWithCookie("/api/v1/auth/refresh", secondGenCookie);
        assertThat(secondRefresh.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * Reuse уже отозванного токена должен отозвать ВСЮ цепочку, порождённую
     * от него дальше — не только сам предъявленный токен. Иначе тот, кто
     * скопировал старый секрет, и легитимный пользователь продолжали бы
     * параллельно ротировать сессию, каждый со своим действующим токеном.
     */
    @Test
    void refresh_reuseOfRotatedToken_revokesTheWholeDownstreamChain() {
        Session session = registerAndCaptureSession();

        // gen1 -> gen2 (обычная легитимная ротация)
        ResponseEntity<AuthDto.TokenResponse> toGen2 = postWithCookie("/api/v1/auth/refresh", session.refreshCookie);
        String gen2Cookie = extractCookieValue(toGen2);

        // gen2 -> gen3 (тоже легитимно)
        ResponseEntity<AuthDto.TokenResponse> toGen3 = postWithCookie("/api/v1/auth/refresh", gen2Cookie);
        assertThat(toGen3.getStatusCode()).isEqualTo(HttpStatus.OK);
        String gen3Cookie = extractCookieValue(toGen3);

        // Атакующий (или баг клиента) предъявляет УЖЕ отозванный gen1 —
        // reuse-detection должен сработать...
        ResponseEntity<AuthDto.TokenResponse> reuseGen1 = postWithCookie("/api/v1/auth/refresh", session.refreshCookie);
        assertThat(reuseGen1.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // ...и откатить действующий на тот момент gen3 тоже, а не только
        // отказать в самом gen1 — легитимный пользователь должен быть
        // вынужден перелогиниться, а не продолжать жить с скомпрометированной цепочкой.
        ResponseEntity<AuthDto.TokenResponse> gen3AfterReuse = postWithCookie("/api/v1/auth/refresh", gen3Cookie);
        assertThat(gen3AfterReuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logout_revokesRefreshCookie_subsequentRefreshFails() {
        Session session = registerAndCaptureSession();

        ResponseEntity<Void> logout = postWithCookie("/api/v1/auth/logout", session.refreshCookie, Void.class);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<AuthDto.TokenResponse> refreshAfterLogout = postWithCookie("/api/v1/auth/refresh", session.refreshCookie);
        assertThat(refreshAfterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refresh_withoutAnyCookie_isUnauthorized() {
        ResponseEntity<AuthDto.TokenResponse> response = rest.postForEntity(
                "/api/v1/auth/refresh", null, AuthDto.TokenResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private Session registerAndCaptureSession() {
        AuthDto.RegisterRequest request = new AuthDto.RegisterRequest(
                "it-" + UUID.randomUUID() + "@example.com", "correct horse battery staple", "IT User");
        ResponseEntity<AuthDto.TokenResponse> response = rest.postForEntity(
                "/api/v1/auth/register", request, AuthDto.TokenResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String refreshCookie = extractCookieValue(response);
        return new Session(response.getBody().accessToken(), refreshCookie);
    }

    private ResponseEntity<AuthDto.TokenResponse> postWithCookie(String path, String cookieValue) {
        return postWithCookie(path, cookieValue, AuthDto.TokenResponse.class);
    }

    private <T> ResponseEntity<T> postWithCookie(String path, String cookieValue, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "refreshToken=" + cookieValue);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(headers), responseType);
    }

    /** Из Set-Cookie достаём только значение (до первого ';') — сама cookie несёт ещё httpOnly/Secure/Path/Max-Age атрибуты. */
    private String extractCookieValue(ResponseEntity<?> response) {
        List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(setCookies).isNotNull().isNotEmpty();
        String refreshTokenCookie = setCookies.stream()
                .filter(c -> c.startsWith("refreshToken="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No refreshToken cookie in Set-Cookie headers: " + setCookies));
        String withoutName = refreshTokenCookie.substring("refreshToken=".length());
        return withoutName.substring(0, withoutName.indexOf(';'));
    }

    private record Session(String accessToken, String refreshCookie) {}
}
