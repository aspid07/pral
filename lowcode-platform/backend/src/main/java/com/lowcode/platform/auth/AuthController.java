package com.lowcode.platform.auth;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

/**
 * Не в исходном api-contract.md (аутентификация там explicitly отложена
 * "предмет отдельного security-документа") — добавлено по итогам обсуждения
 * хранения учёток/ролей, Stage 1 из согласованного плана.
 *
 * Access/refresh (эта итерация): access-токен уезжает клиенту в теле ответа
 * (фронт держит его в памяти, не в storage — см. api/auth.ts), refresh —
 * ТОЛЬКО в httpOnly-cookie с Path, ограниченным этим контроллером. Он
 * никогда не попадает в тело JSON и поэтому недоступен из JS на фронте даже
 * теоретически (защита от кражи через XSS) — единственный канал им
 * воспользоваться — сам браузер, автоматически прикладывающий cookie к
 * запросам на /api/v1/auth/**.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String REFRESH_COOKIE_NAME = "refreshToken";
    // Path строго уже, чем /api/v1 — cookie не должна утекать в обычные
    // запросы к остальному API (там ей просто нечего делать, только
    // увеличивает заголовки), только на сами auth-эндпоинты, которым она нужна.
    private static final String REFRESH_COOKIE_PATH = "/api/v1/auth";

    private final AuthService authService;
    private final boolean cookieSecure;
    private final long refreshCookieMaxAgeSeconds;

    public AuthController(AuthService authService,
                           @Value("${app.refresh-token.cookie-secure:true}") boolean cookieSecure,
                           @Value("${app.refresh-token.ttl-days:7}") long refreshTokenTtlDays) {
        this.authService = authService;
        this.cookieSecure = cookieSecure;
        this.refreshCookieMaxAgeSeconds = Duration.ofDays(refreshTokenTtlDays).toSeconds();
    }

    @PostMapping("/register")
    public ResponseEntity<AuthDto.TokenResponse> register(@Valid @RequestBody AuthDto.RegisterRequest request) {
        return sessionResponse(authService.register(request), HttpStatus.CREATED);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthDto.TokenResponse> login(@Valid @RequestBody AuthDto.LoginRequest request) {
        return sessionResponse(authService.login(request), HttpStatus.OK);
    }

    /**
     * Обменивает refresh-cookie на новую пару access/refresh (ротация — см.
     * RefreshTokenService). Тело запроса не нужно и не читается: единственный
     * источник refresh-токена — cookie, тело для него не предусмотрено
     * намеренно (иначе httpOnly ничего бы не защищал — токен всё равно можно
     * было бы прочитать из JS и положить в body самому).
     *
     * {@code required = false}: отсутствие cookie — ожидаемый штатный случай
     * (сессии не было или её уже отозвали), не программная ошибка — 401, а
     * не 400/500.
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthDto.TokenResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshToken) {
        if (refreshToken == null) {
            throw new BadCredentialsException("No refresh token");
        }
        return sessionResponse(authService.refresh(refreshToken), HttpStatus.OK);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshToken) {
        if (refreshToken != null) {
            authService.logout(refreshToken);
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, expiredCookie().toString())
                .build();
    }

    private ResponseEntity<AuthDto.TokenResponse> sessionResponse(AuthService.Session session, HttpStatus status) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, refreshCookie(session.refreshToken()).toString())
                .body(session.tokenResponse());
    }

    private ResponseCookie refreshCookie(String rawToken) {
        return ResponseCookie.from(REFRESH_COOKIE_NAME, rawToken)
                .httpOnly(true)
                .secure(cookieSecure)
                // Strict, не Lax: refresh-cookie нужна ровно на same-site запросах
                // от нашего же фронта (proxy/nginx same-origin, см. vite.config.ts,
                // nginx.conf) — top-level cross-site переход по ссылке (тот случай,
                // для которого Lax сделал бы исключение) не наш сценарий использования.
                .sameSite("Strict")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(refreshCookieMaxAgeSeconds)
                .build();
    }

    private ResponseCookie expiredCookie() {
        return ResponseCookie.from(REFRESH_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(0)
                .build();
    }
}
