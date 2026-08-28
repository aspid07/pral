package com.lowcode.platform.sharing;

import com.lowcode.platform.auth.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** См. api-contract.md, раздел "REST: Сценарии" (share/collaborators). */
@RestController
@RequestMapping("/api/v1/scenarios/{scenarioId}")
public class CollaboratorController {

    private final CollaboratorService collaboratorService;
    private final CurrentUser currentUser;

    public CollaboratorController(CollaboratorService collaboratorService, CurrentUser currentUser) {
        this.collaboratorService = collaboratorService;
        this.currentUser = currentUser;
    }

    @PostMapping("/share")
    public ResponseEntity<CollaboratorDto.Response> share(@PathVariable UUID scenarioId,
                                                            @Valid @RequestBody CollaboratorDto.ShareRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(collaboratorService.share(scenarioId, request, currentUser.id()));
    }

    @GetMapping("/collaborators")
    public List<CollaboratorDto.Response> list(@PathVariable UUID scenarioId) {
        return collaboratorService.list(scenarioId, currentUser.id());
    }

    @DeleteMapping("/collaborators/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID scenarioId, @PathVariable UUID userId) {
        collaboratorService.revoke(scenarioId, userId, currentUser.id());
    }
}
