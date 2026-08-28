package com.lowcode.platform.execution;

import com.lowcode.platform.auth.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Не входит в исходный api-contract.md — добавлено для мульти-проектного
 * холста (UC5, "единый холст со всеми участвующими проектами"). Фронтенд
 * дёргает это перед запуском run, чтобы построить compound-раскладку через
 * elkjs (проекты — контейнеры, блоки — вложенные узлы).
 */
@RestController
public class ScenarioGraphController {

    private final ScenarioGraphService scenarioGraphService;
    private final CurrentUser currentUser;

    public ScenarioGraphController(ScenarioGraphService scenarioGraphService, CurrentUser currentUser) {
        this.scenarioGraphService = scenarioGraphService;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/v1/scenarios/{scenarioId}/graph")
    public ScenarioGraphDto.Response get(@PathVariable UUID scenarioId) {
        return scenarioGraphService.build(scenarioId, currentUser.id());
    }
}
