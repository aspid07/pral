// Единственная система сборки (Gradle) — pom.xml удалён по ревью CTO, п.2.9
// ("две системы сборки поддерживаются вручную = гарантированно разойдутся").
plugins {
    java
    id("org.springframework.boot") version "3.3.0"
    // Даёт то же самое BOM-управление версиями, что даёт spring-boot-starter-parent
    // в Maven — именно поэтому ниже у большинства зависимостей нет версии.
    id("io.spring.dependency-management") version "1.1.5"
}

group = "com.lowcode"
version = "0.1.0-SNAPSHOT"
description = "Live-конструктор архитектур: backend, модульный монолит"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    // JWT для собственной аутентификации — версия закреплена явно (0.11.5,
    // не последняя 0.12.x с другим fluent API).
    implementation("io.jsonwebtoken:jjwt-api:0.11.5")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.11.5")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.11.5")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    // Версия явная — Spring Boot BOM про Spring Modulith не знает (отдельный проект).
    implementation("org.springframework.modulith:spring-modulith-starter-core:1.2.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // TestRestTemplate по умолчанию использует SimpleClientHttpRequestFactory
    // (JDK HttpURLConnection) — у него несколько известных проблем, которые
    // реально проявились в интеграционных тестах: PATCH не поддерживается
    // штатно (ProtocolException — HttpURLConnection в принципе не умеет в
    // PATCH, Spring подменяет метод рефлексией, что не всегда срабатывает),
    // и POST-запрос, получивший 401, кидает HttpRetryException вместо того,
    // чтобы просто вернуть ResponseEntity со статусом (JDK пытается провести
    // retry аутентификации и не может сделать это в streaming-режиме, раз
    // тело запроса уже отправлено). Версия не указана явно — берётся из
    // spring-boot-dependencies BOM (см. id("io.spring.dependency-management")
    // выше), совместима с зафиксированной версией Spring Boot автоматически.
    testImplementation("org.apache.httpcomponents.client5:httpclient5")
    // Интеграционный тест против настоящего Postgres (не мока) — см.
    // BlockAndEntryPointDeleteCascadeIntegrationTest. Версии
    // берутся из testcontainers-bom, который сюда уже протащил spring-boot-dependencies.
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
