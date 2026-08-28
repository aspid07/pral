package com.lowcode.platform.domain.api;

import com.lowcode.platform.domain.model.Connection;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public class ConnectionDto {

    public record CreateRequest(
            @NotNull UUID sourceBlockId,
            @NotNull UUID targetBlockId,
            @NotNull Connection.IntegrationType integrationType) {}

    public record Response(
            UUID id,
            UUID schemeId,
            UUID sourceBlockId,
            UUID targetBlockId,
            Connection.IntegrationType integrationType,
            boolean isExternal) {}
}
