package com.lowcode.platform.config;

import com.lowcode.platform.auth.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stage 4 плана auth/ролей (см. ревью CTO, п.1.1): реальные ограничения
 * включены. /auth/** — открыт (иначе логин/регистрация невозможны), всё
 * остальное требует валидного JWT. Ролевая авторизация (кто именно что может
 * редактировать) — отдельный уровень поверх этого, через PermissionService
 * в конкретных сервисах (см. sharing/), не здесь.
 */
@Configuration
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        // Ревью CTO, п.2.4: браузерный WebSocket API не даёт поставить
                        // кастомные заголовки на handshake — Authorization: Bearer
                        // физически нельзя приложить к /ws/runs. Если бы этот путь
                        // требовал authenticated(), вся визуализация исполнения
                        // сценариев сломалась бы молча. Оставляем permitAll здесь
                        // осознанно — правильная защита (STOMP CONNECT-фрейм через
                        // ChannelInterceptor, проверка прав на SUBSCRIBE) — отдельная
                        // задача, ещё не сделана.
                        .requestMatchers("/ws/**").permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}

