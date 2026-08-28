package com.lowcode.platform.domain.api;

import com.lowcode.platform.auth.CurrentUser;
import com.lowcode.platform.domain.service.ConnectionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

/**
 * Пути смешанные (см. api-contract.md): создание — под проектом, а операции
 * над конкретной связью — глобально по /connections/{id}, поэтому без
 * общего class-level @RequestMapping.
 */
@RestController
public class ConnectionController {

    private final ConnectionService connectionService;
    private final CurrentUser currentUser;

    public ConnectionController(ConnectionService connectionService, CurrentUser currentUser) {
        this.connectionService = connectionService;
        this.currentUser = currentUser;
    }

    @PostMapping("/api/v1/projects/{projectId}/connections")
    public ResponseEntity<ConnectionDto.Response> create(@PathVariable UUID projectId,
                                                           @Valid @RequestBody ConnectionDto.CreateRequest request) {
        ConnectionDto.Response created = connectionService.create(projectId, request, currentUser.id());
        return ResponseEntity.created(URI.create("/api/v1/connections/" + created.id())).body(created);
    }

    @GetMapping("/api/v1/connections/{connectionId}")
    public ConnectionDto.Response get(@PathVariable UUID connectionId) {
        return connectionService.get(connectionId, currentUser.id());
    }

    @DeleteMapping("/api/v1/connections/{connectionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID connectionId) {
        connectionService.delete(connectionId, currentUser.id());
    }
}
