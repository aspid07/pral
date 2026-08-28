package com.lowcode.platform.domain.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public class ScenarioDto {

    // Stage 4: ownerId убран — источник теперь CurrentUser.id() из
    // SecurityContext (ScenarioController.create()), а не тело запроса
    // (см. ревью CTO, п.1.2 — раньше пользователь A мог создать сценарий
    // с ownerId пользователя B).
    public record CreateRequest(@NotBlank String name, @NotNull UUID entryPointId) {}

    // PATCH-семантика: null = "не менять" (см. BlockInstanceDto.UpdateRequest).
    // Даже при единственном поле — без этого пустой/частичный PATCH уронил бы
    // name в null и упёрся в not-null constraint на scenario.name.
    public record UpdateRequest(String name) {}

    public record Response(UUID id, String name, UUID entryPointId, UUID ownerId) {}
}
