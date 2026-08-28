package com.lowcode.platform.execution;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Персистентный статус запуска сценария — нужен для GET /runs/{runId}
 * (api-contract.md: "running / completed / failed"). Живые события хода
 * исполнения идут отдельно, через ExecutionEventPublisher/WebSocket; эта
 * запись — только итоговый статус, переживающий разрыв WS-соединения.
 */
@Entity
@Table(name = "execution_run")
public class ExecutionRun {

    public enum Status { RUNNING, PAUSED, COMPLETED, FAILED, STOPPED }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "scenario_id", nullable = false)
    private UUID scenarioId;

    // Порядковый номер запуска ЭТОГО сценария (1, 2, 3...) — для UI ("Запуск #N"),
    // считается в RunService при создании записи.
    @Column(name = "run_number", nullable = false)
    private int runNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;

    public UUID getId() { return id; }
    public UUID getScenarioId() { return scenarioId; }
    public void setScenarioId(UUID scenarioId) { this.scenarioId = scenarioId; }
    public int getRunNumber() { return runNumber; }
    public void setRunNumber(int runNumber) { this.runNumber = runNumber; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}
