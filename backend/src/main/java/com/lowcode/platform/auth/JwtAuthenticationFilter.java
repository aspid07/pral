package com.lowcode.platform.auth;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Распознаёт Bearer-токен и кладёт userId в SecurityContext. С Stage 4
 * SecurityConfig требует authenticated() почти на всём — если токена нет или
 * он невалиден, запрос сюда даже не долетит с успехом (Spring Security
 * отклонит его 401 раньше, чем дойдёт до контроллера); если endpoint из
 * permitAll() (/auth/**) — работает и без токена, как и должно.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserStatusCache userStatusCache;

    public JwtAuthenticationFilter(JwtService jwtService, UserStatusCache userStatusCache) {
        this.jwtService = jwtService;
        this.userStatusCache = userStatusCache;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring("Bearer ".length());
            try {
                UUID userId = jwtService.parseUserId(token);
                // Ревью CTO, п.1.6: подпись валидна не значит "пользователь всё ещё
                // существует и активен" — токен переживает удаление/деактивацию
                // аккаунта до истечения TTL. Полный refresh-token флоу с отзывом —
                // отдельная задача следующей итерации; это минимальная защита от
                // самого частого случая ("уволили сотрудника") прямо сейчас.
                // Кеш на 30с (UserStatusCache), чтобы не бить БД на каждый запрос.
                if (userStatusCache.isEnabled(userId)) {
                    var authentication = new UsernamePasswordAuthenticationToken(userId, null, List.of());
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } else {
                    SecurityContextHolder.clearContext();
                }
            } catch (JwtException | IllegalArgumentException e) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
