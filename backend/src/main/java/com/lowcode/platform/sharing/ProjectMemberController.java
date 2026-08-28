package com.lowcode.platform.sharing;

import com.lowcode.platform.auth.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Не в исходном api-contract.md — добавлено вместе со Stage 2 плана
 *  auth/ролей (роли на уровне Project, зеркалит /scenarios/{id}/collaborators). */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/members")
public class ProjectMemberController {

    private final ProjectMemberService projectMemberService;
    private final CurrentUser currentUser;

    public ProjectMemberController(ProjectMemberService projectMemberService, CurrentUser currentUser) {
        this.projectMemberService = projectMemberService;
        this.currentUser = currentUser;
    }

    @PostMapping
    public ResponseEntity<ProjectMemberDto.Response> grant(@PathVariable UUID projectId,
                                                            @Valid @RequestBody ProjectMemberDto.GrantRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(projectMemberService.grant(projectId, request, currentUser.id()));
    }

    @GetMapping
    public List<ProjectMemberDto.Response> list(@PathVariable UUID projectId) {
        return projectMemberService.list(projectId, currentUser.id());
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID projectId, @PathVariable UUID userId) {
        projectMemberService.revoke(projectId, userId, currentUser.id());
    }
}
