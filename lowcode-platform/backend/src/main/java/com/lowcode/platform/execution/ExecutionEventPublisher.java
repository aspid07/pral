package com.lowcode.platform.execution;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Публикует события исполнения в STOMP-топик /topic/runs/{runId}.
 * Схема событий — см. api-contract.md, раздел "WebSocket: события исполнения"
 * (включая "Решение: parallel-вызовы в WebSocket-схеме").
 *
 * Все payload-записи несут поле type — все события идут в один топик
 * (/topic/runs/{runId}), и без явного дискриминатора фронтенд не сможет
 * различить их по форме JSON (см. frontend/src/ws/executionSocket.ts).
 */
@Component
public class ExecutionEventPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    public ExecutionEventPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void publishRunStarted(UUID runId, UUID scenarioId) {
        messagingTemplate.convertAndSend("/topic/runs/" + runId,
                new RunStarted(runId, scenarioId));
    }

    public void publishStepStarted(UUID runId, UUID stepId, UUID sourceEntryPointId, UUID targetEntryPointId,
                                    String label, String kind, String parallelGroupId, Integer timeoutMs) {
        messagingTemplate.convertAndSend("/topic/runs/" + runId,
                new StepStarted(runId, stepId, sourceEntryPointId, targetEntryPointId, label, kind,
                        parallelGroupId, timeoutMs));
    }

    public void publishClusterEntered(UUID runId, UUID projectId) {
        messagingTemplate.convertAndSend("/topic/runs/" + runId,
                new ClusterEntered(runId, projectId));
    }

    public void publishStepCompleted(UUID runId, UUID stepId) {
        messagingTemplate.convertAndSend("/topic/runs/" + runId,
                new StepCompleted(runId, stepId));
    }

    /** stepId — id самого RETRY-узла (обёртки), а не вложенных шагов, которые он оборачивает. */
    public void publishStepRetrying(UUID runId, UUID stepId, int attempt, int maxAttempts) {
        messagingTemplate.convertAndSend("/topic/runs/" + runId,
                new StepRetrying(runId, stepId, attempt, maxAttempts));
    }

    /** stepId — id самого TIMEOUT-узла. См. ScenarioStep.timeoutMs и ExecutionEngine.executeTimeout. */
    public void publishStepTimeout(UUID runId, UUID stepId) {
        messagingTemplate.convertAndSend("/topic/runs/" + runId,
                new StepTimeout(runId, stepId));
    }

    public void publishRunCompleted(UUID runId) {
        messagingTemplate.convertAndSend("/topic/runs/" + runId, new RunCompleted(runId));
    }

    public void publishRunError(UUID runId, UUID stepId, String message) {
        messagingTemplate.convertAndSend("/topic/runs/" + runId,
                new RunError(runId, stepId, message));
    }

    /**
     * stepId — последний ЗАВЕРШЁННЫЙ шаг на момент паузы (null, если пауза
     * сработала до самого первого шага) — движок блокируется РОВНО здесь
     * (см. ExecutionEngine.checkpoint), поэтому resume продолжает без
     * необходимости где-либо восстанавливать позицию отдельно.
     */
    public void publishRunPaused(UUID runId, UUID stepId) {
        messagingTemplate.convertAndSend("/topic/runs/" + runId, new RunPaused(runId, stepId));
    }

    public void publishRunResumed(UUID runId) {
        messagingTemplate.convertAndSend("/topic/runs/" + runId, new RunResumed(runId));
    }

    /** stepId — та же семантика, что и у publishRunPaused: последний завершённый шаг на момент остановки. */
    public void publishRunStopped(UUID runId, UUID stepId) {
        messagingTemplate.convertAndSend("/topic/runs/" + runId, new RunStopped(runId, stepId));
    }

    // Каждая запись несёт "type" как первое поле сериализованного JSON — фронтенд
    // switch'ится на него (см. ExecutionEvent union в executionSocket.ts).
    // Канонический конструктор записи приватный/полный, наружу торчит только
    // компактный overload без type, чтобы вызывающий код (методы выше) не мог
    // случайно подставить неправильную строку-дискриминатор.

    public record RunStarted(String type, UUID runId, UUID scenarioId) {
        public RunStarted(UUID runId, UUID scenarioId) { this("RUN_STARTED", runId, scenarioId); }
    }

    public record StepStarted(String type, UUID runId, UUID stepId, UUID sourceEntryPointId, UUID targetEntryPointId,
                               String label, String kind, String parallelGroupId, Integer timeoutMs) {
        public StepStarted(UUID runId, UUID stepId, UUID sourceEntryPointId, UUID targetEntryPointId,
                            String label, String kind, String parallelGroupId, Integer timeoutMs) {
            this("STEP_STARTED", runId, stepId, sourceEntryPointId, targetEntryPointId, label, kind,
                    parallelGroupId, timeoutMs);
        }
    }

    public record ClusterEntered(String type, UUID runId, UUID projectId) {
        public ClusterEntered(UUID runId, UUID projectId) { this("CLUSTER_ENTERED", runId, projectId); }
    }

    public record StepCompleted(String type, UUID runId, UUID stepId) {
        public StepCompleted(UUID runId, UUID stepId) { this("STEP_COMPLETED", runId, stepId); }
    }

    public record StepRetrying(String type, UUID runId, UUID stepId, int attempt, int maxAttempts) {
        public StepRetrying(UUID runId, UUID stepId, int attempt, int maxAttempts) {
            this("STEP_RETRYING", runId, stepId, attempt, maxAttempts);
        }
    }

    public record StepTimeout(String type, UUID runId, UUID stepId) {
        public StepTimeout(UUID runId, UUID stepId) { this("STEP_TIMEOUT", runId, stepId); }
    }

    public record RunCompleted(String type, UUID runId) {
        public RunCompleted(UUID runId) { this("RUN_COMPLETED", runId); }
    }

    public record RunError(String type, UUID runId, UUID stepId, String message) {
        public RunError(UUID runId, UUID stepId, String message) { this("RUN_ERROR", runId, stepId, message); }
    }

    public record RunPaused(String type, UUID runId, UUID stepId) {
        public RunPaused(UUID runId, UUID stepId) { this("RUN_PAUSED", runId, stepId); }
    }

    public record RunResumed(String type, UUID runId) {
        public RunResumed(UUID runId) { this("RUN_RESUMED", runId); }
    }

    public record RunStopped(String type, UUID runId, UUID stepId) {
        public RunStopped(UUID runId, UUID stepId) { this("RUN_STOPPED", runId, stepId); }
    }
}
