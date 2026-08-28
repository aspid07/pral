package com.lowcode.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.cache.annotation.EnableCaching;

/**
 * Точка входа. Модульный монолит: границы модулей — domain, execution,
 * versioning, sharing, auth — см. пакеты верхнего уровня и architecture.md.
 * @EnableCaching — для BlockTypeLookupService (статический справочник, без
 * CRUD, кешируется в памяти на весь uptime приложения).
 * exclude UserDetailsServiceAutoConfiguration — своя JWT-аутентификация
 * (auth/), встроенный in-memory пользователь со случайным паролем в логах
 * больше не нужен и только путает (никакого basic-auth/form-login мы не используем).
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableCaching
public class PlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
