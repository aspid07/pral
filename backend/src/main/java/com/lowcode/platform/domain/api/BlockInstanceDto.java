package com.lowcode.platform.domain.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public class BlockInstanceDto {

    public record CreateRequest(
            @NotNull UUID blockTypeId,
            @NotBlank String label,
            double x,
            double y) {}

    // PATCH-семантика: поле, отсутствующее в JSON (Jackson десериализует как null),
    // означает "не менять" — не "очистить". Ни у одного из этих полей нет
    // легитимного доменного значения null, так что конвенция безопасна без
    // отдельного wrapper-типа под "поле явно не передано vs явно обнулено".
    public record UpdateRequest(
            String label,
            Double x,
            Double y) {}

    public record Response(
            UUID id,
            UUID schemeId,
            UUID blockTypeId,
            String blockTypeCode,
            String label,
            double x,
            double y) {}
}
