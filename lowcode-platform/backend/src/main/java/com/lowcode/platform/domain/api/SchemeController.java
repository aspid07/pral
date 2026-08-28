package com.lowcode.platform.domain.api;

import com.lowcode.platform.auth.CurrentUser;
import com.lowcode.platform.domain.service.SchemeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/scheme")
public class SchemeController {

    private final SchemeService schemeService;
    private final CurrentUser currentUser;

    public SchemeController(SchemeService schemeService, CurrentUser currentUser) {
        this.schemeService = schemeService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public SchemeDto.Response get(@PathVariable UUID projectId) {
        return schemeService.getByProject(projectId, currentUser.id());
    }
}
