package com.lowcode.platform.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Stage 4: единая точка извлечения userId текущего пользователя вместо
 * дублирования приведения типов в каждом контроллере. SecurityConfig
 * требует authenticated() на защищённых путях, так что Spring Security сам
 * отклонит запрос 401 до того, как он дойдёт до контроллера — исключение
 * здесь защитное, а не ожидаемый рабочий путь.
 */
@Component
public class CurrentUser {

    public UUID id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UUID userId)) {
            throw new IllegalStateException("No authenticated user in SecurityContext");
        }
        return userId;
    }
}
