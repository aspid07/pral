package com.lowcode.platform.versioning;

import com.lowcode.platform.auth.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** См. api-contract.md, раздел "REST: Сценарии" (versions). */
@RestController
@RequestMapping("/api/v1/scenarios/{scenarioId}/versions")
public class VersionController {

    private final VersioningService versioningService;
    private final CurrentUser currentUser;

    public VersionController(VersioningService versioningService, CurrentUser currentUser) {
        this.versioningService = versioningService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<VersionDto.Summary> list(@PathVariable UUID scenarioId) {
        return versioningService.list(scenarioId, currentUser.id());
    }

    @GetMapping("/{versionId}")
    public VersionDto.Detail get(@PathVariable UUID scenarioId, @PathVariable UUID versionId) {
        return versioningService.get(scenarioId, versionId, currentUser.id());
    }
}
