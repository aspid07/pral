package com.lowcode.platform.sharing;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public class CollaboratorDto {

    // api-contract.md: "Создать ссылку-приглашение с ролью (Editor / Reader)".
    // Упрощение для MVP: без реального auth/сессий "приглашение по ссылке" не
    // на что вешать — принимаем userId напрямую, как если бы ссылку уже приняли.
    // Роль OWNER через share выдать нельзя — она проставляется один раз,
    // автоматически, при создании сценария (см. ScenarioService.create()).
    public record ShareRequest(@NotNull UUID userId, @NotNull Role role) {}

    public record Response(UUID id, UUID scenarioId, UUID userId, Role role) {}
}
