package com.lowcode.platform.domain.api;

import com.lowcode.platform.auth.CurrentUser;
import com.lowcode.platform.domain.service.ProjectService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

/**
 * См. api-contract.md, раздел "REST: Проекты и Общая схема".
 * Пагинация — стандартный Spring Data Pageable (?page=&size=&sort=).
 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final CurrentUser currentUser;

    public ProjectController(ProjectService projectService, CurrentUser currentUser) {
        this.projectService = projectService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public Page<ProjectDto.Response> list(Pageable pageable) {
        return projectService.list(currentUser.id(), pageable);
    }

    @PostMapping
    public ResponseEntity<ProjectDto.Response> create(@Valid @RequestBody ProjectDto.CreateRequest request) {
        ProjectDto.Response created = projectService.create(request, currentUser.id());
        return ResponseEntity.created(URI.create("/api/v1/projects/" + created.id())).body(created);
    }

    @GetMapping("/{projectId}")
    public ProjectDto.Response get(@PathVariable UUID projectId) {
        return projectService.get(projectId, currentUser.id());
    }

    @PatchMapping("/{projectId}")
    public ProjectDto.Response update(@PathVariable UUID projectId,
                                       @Valid @RequestBody ProjectDto.UpdateRequest request) {
        return projectService.update(projectId, request, currentUser.id());
    }

    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID projectId) {
        projectService.delete(projectId, currentUser.id());
    }
}
