package com.lowcode.platform.domain.api;

import com.lowcode.platform.domain.model.EntryPoint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public class EntryPointDto {

    public record CreateRequest(@NotBlank String name, @NotNull EntryPoint.Kind kind) {}

    // PATCH-семантика: null = "не менять" (см. комментарий в BlockInstanceDto.UpdateRequest).
    public record UpdateRequest(String name, EntryPoint.Kind kind) {}

    public record Response(UUID id, UUID blockInstanceId, String name, EntryPoint.Kind kind) {}
}
