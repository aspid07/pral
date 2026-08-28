package com.lowcode.platform.domain.api;

import com.lowcode.platform.auth.CurrentUser;
import com.lowcode.platform.domain.service.ScenarioService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/scenarios")
public class ScenarioController {

    private final ScenarioService scenarioService;
    private final CurrentUser currentUser;

    public ScenarioController(ScenarioService scenarioService, CurrentUser currentUser) {
        this.scenarioService = scenarioService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public Page<ScenarioDto.Response> list(Pageable pageable) {
        return scenarioService.list(currentUser.id(), pageable);
    }

    @PostMapping
    public ResponseEntity<ScenarioDto.Response> create(@Valid @RequestBody ScenarioDto.CreateRequest request) {
        ScenarioDto.Response created = scenarioService.create(request, currentUser.id());
        return ResponseEntity.created(URI.create("/api/v1/scenarios/" + created.id())).body(created);
    }

    @GetMapping("/{scenarioId}")
    public ScenarioDto.Response get(@PathVariable UUID scenarioId) {
        return scenarioService.get(scenarioId, currentUser.id());
    }

    @PatchMapping("/{scenarioId}")
    public ScenarioDto.Response update(@PathVariable UUID scenarioId,
                                        @Valid @RequestBody ScenarioDto.UpdateRequest request) {
        return scenarioService.update(scenarioId, request, currentUser.id());
    }

    @DeleteMapping("/{scenarioId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID scenarioId) {
        scenarioService.delete(scenarioId, currentUser.id());
    }
}
