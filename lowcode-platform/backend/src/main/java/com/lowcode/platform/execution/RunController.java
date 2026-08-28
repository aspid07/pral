package com.lowcode.platform.execution;

import com.lowcode.platform.auth.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

/** См. api-contract.md, раздел "REST: Запуск сценария". */
@RestController
public class RunController {

    private final RunService runService;
    private final CurrentUser currentUser;

    public RunController(RunService runService, CurrentUser currentUser) {
        this.runService = runService;
        this.currentUser = currentUser;
    }

    @PostMapping("/api/v1/scenarios/{scenarioId}/runs")
    public ResponseEntity<RunDto.StartResponse> start(@PathVariable UUID scenarioId,
                                                        @RequestBody(required = false) RunDto.StartRequest request) {
        Map<UUID, UUID> branchSelections = request != null && request.branchSelections() != null
                ? request.branchSelections() : Map.of();
        ExecutionRun run = runService.start(scenarioId, branchSelections, currentUser.id());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/v1/runs/" + run.getId()))
                .body(new RunDto.StartResponse(run.getId(), run.getRunNumber()));
    }

    @GetMapping("/api/v1/runs/{runId}")
    public RunDto.StatusResponse status(@PathVariable UUID runId) {
        ExecutionRun run = runService.getStatus(runId, currentUser.id());
        return new RunDto.StatusResponse(
                run.getId(), run.getScenarioId(), run.getRunNumber(),
                run.getStatus().name().toLowerCase(), run.getErrorMessage());
    }

    /**
     * 202, не 204: эффект асинхронный — движок реально встанет на паузу на
     * следующем checkpoint'е (см. ExecutionEngine.checkpoint), не в момент
     * ответа на этот запрос. Живое подтверждение — событие RUN_PAUSED в WS;
     * этот ответ означает "запрос принят", не "уже на паузе".
     */
    @PostMapping("/api/v1/runs/{runId}/pause")
    public ResponseEntity<Void> pause(@PathVariable UUID runId) {
        runService.requestPause(runId, currentUser.id());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/api/v1/runs/{runId}/resume")
    public ResponseEntity<Void> resume(@PathVariable UUID runId) {
        runService.requestResume(runId, currentUser.id());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/api/v1/runs/{runId}/stop")
    public ResponseEntity<Void> stop(@PathVariable UUID runId) {
        runService.requestStop(runId, currentUser.id());
        return ResponseEntity.accepted().build();
    }
}
