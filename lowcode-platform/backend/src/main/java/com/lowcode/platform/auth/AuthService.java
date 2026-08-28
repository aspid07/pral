package com.lowcode.platform.auth;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stage 1 (аутентификация): регистрация открыта всем без проверки прав —
 * сознательное упрощение, чтобы можно было end-to-end проверить логин/JWT.
 * Для реального разворачивания "на компанию" стоит решить: то ли открытая
 * регистрация с ограничением по домену почты, то ли только через
 * админ-инструмент/приглашение — это отдельный вопрос, не Stage 1.
 *
 * Access/refresh (эта итерация): каждый выданный {@link Session} несёт ОБА
 * токена — короткоживущий access (JwtService, ~15 мин, самодостаточный,
 * проверяется на каждый запрос без обращения к БД) и refresh (RefreshTokenService,
 * sliding 7 дней, непрозрачный, живёт в БД, единственный способ получить новый
 * access без повторного логина/пароля). AuthController решает, куда каждый из
 * них положить в HTTP-ответе (тело vs httpOnly-cookie) — это уже транспортный
 * вопрос, не бизнес-логика сессии, поэтому здесь не участвует.
 */
@Service
public class AuthService {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    public AuthService(AppUserRepository userRepository,
                        PasswordEncoder passwordEncoder,
                        JwtService jwtService,
                        RefreshTokenService refreshTokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional
    public Session register(AuthDto.RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            // Ревью CTO, п.2.6: раньше сообщение включало сам email — прямой
            // оракул "кто из сотрудников уже зарегистрирован". Убрали email из
            // текста; статус-код (409 vs 201) всё ещё различим — полноценное
            // решение (одинаковый ответ + письмо владельцу адреса) требует
            // email-инфраструктуры, которой пока нет, задача отдельно в бэклоге.
            throw new IllegalStateException("This email is already registered");
        }
        AppUser user = new AppUser();
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(request.displayName());
        user = userRepository.save(user);
        return issueSession(user);
    }

    // Раньше было @Transactional(readOnly = true) — верно для чистого login
    // без побочных эффектов, но issueSession теперь ещё и ПИШЕТ строку
    // refresh_token; readOnly-транзакция на Postgres переводит соединение в
    // read-only на уровне JDBC-драйвера, и такая запись просто упадёт.
    @Transactional
    public Session login(AuthDto.LoginRequest request) {
        AppUser user = userRepository.findByEmail(request.email())
                // Умышленно то же сообщение, что и для неверного пароля ниже —
                // не подтверждаем существование email на этапе логина
                // (иначе логин-форма превращается в оракул "есть ли такой юзер").
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }
        return issueSession(user);
    }

    /**
     * Обменивает валидный refresh-токен на новую пару access/refresh
     * (ротация — см. RefreshTokenService.rotate). Отдельно от JwtAuthenticationFilter
     * проверяет {@code enabled}: там это TTL-кеш на 30с ради каждого обычного
     * запроса, здесь же refresh и так происходит редко (раз в ~15 минут на
     * активного пользователя) — можно позволить себе честный поход в БД и не
     * держать протухший (до 30с) enabled=true в кеше именно в точке выдачи
     * нового access-токена.
     */
    @Transactional
    public Session refresh(String refreshToken) {
        RefreshTokenService.RotationResult rotated = refreshTokenService.rotate(refreshToken);
        AppUser user = userRepository.findById(rotated.userId())
                .filter(AppUser::isEnabled)
                .orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));
        String accessToken = jwtService.generate(user.getId(), user.getEmail());
        AuthDto.TokenResponse response =
                new AuthDto.TokenResponse(accessToken, user.getId(), user.getEmail(), user.getDisplayName());
        return new Session(response, rotated.rawToken());
    }

    /** Отзывает refresh-токен. Идемпотентно — повторный вызов с тем же (уже отозванным) токеном не ошибка. */
    @Transactional
    public void logout(String refreshToken) {
        refreshTokenService.revoke(refreshToken);
    }

    private Session issueSession(AppUser user) {
        String accessToken = jwtService.generate(user.getId(), user.getEmail());
        String refreshToken = refreshTokenService.issue(user.getId());
        AuthDto.TokenResponse response =
                new AuthDto.TokenResponse(accessToken, user.getId(), user.getEmail(), user.getDisplayName());
        return new Session(response, refreshToken);
    }

    /** Пара результатов выдачи сессии: тело HTTP-ответа + сырой refresh-токен для cookie. */
    public record Session(AuthDto.TokenResponse tokenResponse, String refreshToken) {}
}
