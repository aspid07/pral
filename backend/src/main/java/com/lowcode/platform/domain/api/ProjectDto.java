package com.lowcode.platform.domain.api;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.UUID;

public class ProjectDto {

    // Stage 4: ownerId убран отсюда — клиент больше не может заявить "я вот
    // этот UUID" (см. ревью CTO, п.1.2). Источник — CurrentUser.id() из
    // SecurityContext (ProjectController.create()), всегда доступен, т.к.
    // эндпоинт теперь требует аутентификации. Заодно закрывает п.1.4:
    // проект больше не может остаться без владельца — ownerId всегда есть.
    public record CreateRequest(@NotBlank String name, String description) {}

    public record UpdateRequest(@NotBlank String name, String description) {}

    public record Response(UUID id, String name, String description, UUID schemeId, Instant createdAt) {}
}
