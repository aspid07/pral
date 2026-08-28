package com.lowcode.platform.domain.api;

import com.lowcode.platform.auth.CurrentUser;
import com.lowcode.platform.domain.service.ScenarioStepService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/scenarios/{scenarioId}/steps")
public class ScenarioStepController {

    private final ScenarioStepService scenarioStepService;
    private final CurrentUser currentUser;

    public ScenarioStepController(ScenarioStepService scenarioStepService, CurrentUser currentUser) {
        this.scenarioStepService = scenarioStepService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<ScenarioStepDto.Response> list(@PathVariable UUID scenarioId) {
        return scenarioStepService.list(scenarioId, currentUser.id());
    }

    @PostMapping
    public ResponseEntity<ScenarioStepDto.Response> create(@PathVariable UUID scenarioId,
                                                             @Valid @RequestBody ScenarioStepDto.CreateRequest request) {
        ScenarioStepDto.Response created = scenarioStepService.create(scenarioId, request, currentUser.id());
        return ResponseEntity.created(
                URI.create("/api/v1/scenarios/" + scenarioId + "/steps/" + created.id())).body(created);
    }

    @PatchMapping("/{stepId}")
    public ScenarioStepDto.Response update(@PathVariable UUID scenarioId, @PathVariable UUID stepId,
                                            @Valid @RequestBody ScenarioStepDto.UpdateRequest request) {
        return scenarioStepService.update(scenarioId, stepId, request, currentUser.id());
    }

    @DeleteMapping("/{stepId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID scenarioId, @PathVariable UUID stepId) {
        scenarioStepService.delete(scenarioId, stepId, currentUser.id());
    }
}
