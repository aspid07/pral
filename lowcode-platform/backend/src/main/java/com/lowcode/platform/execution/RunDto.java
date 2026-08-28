package com.lowcode.platform.execution;

import java.util.Map;
import java.util.UUID;

public class RunDto {
    // branchSelections: altStepId -> id выбранного дочернего шага (ветки). Опционально —
    // для ALT-узлов без явного выбора движок берёт первую ветку по order_index
    // (см. ExecutionEngine.executeAlt). Тело запроса целиком необязательно.
    public record StartRequest(Map<UUID, UUID> branchSelections) {}

    public record StartResponse(UUID runId, int runNumber) {}

    public record StatusResponse(UUID runId, UUID scenarioId, int runNumber, String status, String errorMessage) {}
}
