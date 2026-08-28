package com.lowcode.platform.domain.api;

import com.lowcode.platform.domain.model.ScenarioStep;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public class ScenarioStepDto {

    // orderIndex не принимается от клиента — вычисляется сервером как следующий
    // индекс среди шагов с тем же parentStepId (см. ScenarioStepService).
    // maxAttempts обязателен только для stepType=RETRY, timeoutMs — только для
    // stepType=TIMEOUT; для остальных типов оба должны остаться null (валидация
    // в ScenarioStepService.validateStepFields).
    public record CreateRequest(
            @NotNull ScenarioStep.StepType stepType,
            UUID parentStepId,
            UUID calledEntryPointId,
            String conditionLabel,
            String parallelGroupId,
            Integer maxAttempts,
            Integer timeoutMs) {}

    // Реструктуризация (смена stepType/parentStepId/orderIndex) — вне скоупа
    // этой итерации; PATCH меняет только "содержимое" шага.
    public record UpdateRequest(
            UUID calledEntryPointId,
            String conditionLabel,
            String parallelGroupId,
            Integer maxAttempts,
            Integer timeoutMs) {}

    public record Response(
            UUID id,
            UUID scenarioId,
            int orderIndex,
            UUID parentStepId,
            ScenarioStep.StepType stepType,
            UUID calledEntryPointId,
            String conditionLabel,
            String parallelGroupId,
            Integer maxAttempts,
            Integer timeoutMs) {}
}
