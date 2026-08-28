package com.lowcode.platform.domain.model;

import jakarta.persistence.*;
import java.util.UUID;

/**
 * Шаг сценария. Вложенность через parentStepId даёт alt/parallel/retry/timeout
 * без отдельных таблиц под каждый тип ветвления (по аналогии с PlantUML alt/par).
 */
@Entity
@Table(name = "scenario_step")
public class ScenarioStep {

    public enum StepType { CALL, ALT, PARALLEL, RETRY, TIMEOUT }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "scenario_id", nullable = false)
    private UUID scenarioId;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    @Column(name = "parent_step_id")
    private UUID parentStepId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StepType stepType;

    @Column(name = "called_entry_point_id")
    private UUID calledEntryPointId;

    private String conditionLabel; // напр. "cache miss"

    // группировка параллельных вызовов для WebSocket-события STEP_STARTED.parallelGroupId
    private String parallelGroupId;

    // Только для stepType=RETRY: сколько попыток анимировать (STEP_RETRYING) перед
    // финальным (всегда успешным в симуляции — см. ExecutionEngine) выполнением.
    @Column(name = "max_attempts")
    private Integer maxAttempts;

    // Только для stepType=TIMEOUT: бюджет по времени в мс. Реального измерения
    // времени выполнения ExecutionEngine не делает (нет настоящих вызовов) —
    // публикуется во STEP_STARTED как метаданные для фронтенда (напр. индикатор
    // обратного отсчёта); STEP_TIMEOUT сейчас генерируется при ошибке внутри
    // обёрнутых шагов, а не по факту истечения timeoutMs.
    @Column(name = "timeout_ms")
    private Integer timeoutMs;

    public UUID getId() { return id; }
    public UUID getScenarioId() { return scenarioId; }
    public void setScenarioId(UUID scenarioId) { this.scenarioId = scenarioId; }
    public int getOrderIndex() { return orderIndex; }
    public void setOrderIndex(int orderIndex) { this.orderIndex = orderIndex; }
    public UUID getParentStepId() { return parentStepId; }
    public void setParentStepId(UUID parentStepId) { this.parentStepId = parentStepId; }
    public StepType getStepType() { return stepType; }
    public void setStepType(StepType stepType) { this.stepType = stepType; }
    public UUID getCalledEntryPointId() { return calledEntryPointId; }
    public void setCalledEntryPointId(UUID calledEntryPointId) { this.calledEntryPointId = calledEntryPointId; }
    public String getConditionLabel() { return conditionLabel; }
    public void setConditionLabel(String conditionLabel) { this.conditionLabel = conditionLabel; }
    public String getParallelGroupId() { return parallelGroupId; }
    public void setParallelGroupId(String parallelGroupId) { this.parallelGroupId = parallelGroupId; }
    public Integer getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(Integer maxAttempts) { this.maxAttempts = maxAttempts; }
    public Integer getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(Integer timeoutMs) { this.timeoutMs = timeoutMs; }
}
