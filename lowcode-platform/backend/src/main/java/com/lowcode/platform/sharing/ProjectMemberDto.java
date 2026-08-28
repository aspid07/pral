package com.lowcode.platform.sharing;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public class ProjectMemberDto {

    // OWNER через этот эндпоинт не выдать — только EDITOR/READER, как и у
    // Collaborator (см. CollaboratorDto). OWNER пространства выставляется
    // один раз, при создании проекта.
    public record GrantRequest(@NotNull UUID userId, @NotNull Role role) {}

    public record Response(UUID id, UUID projectId, UUID userId, Role role) {}
}
