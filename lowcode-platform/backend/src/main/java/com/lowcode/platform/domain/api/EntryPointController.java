package com.lowcode.platform.domain.api;

import com.lowcode.platform.auth.CurrentUser;
import com.lowcode.platform.domain.service.EntryPointService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/** См. api-contract.md, раздел "REST: Блоки и Entry Point". */
@RestController
public class EntryPointController {

    private final EntryPointService entryPointService;
    private final CurrentUser currentUser;

    public EntryPointController(EntryPointService entryPointService, CurrentUser currentUser) {
        this.entryPointService = entryPointService;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/v1/blocks/{blockId}/entry-points")
    public List<EntryPointDto.Response> listByBlock(@PathVariable UUID blockId) {
        return entryPointService.listByBlock(blockId, currentUser.id());
    }

    @PostMapping("/api/v1/blocks/{blockId}/entry-points")
    public ResponseEntity<EntryPointDto.Response> create(@PathVariable UUID blockId,
                                                           @Valid @RequestBody EntryPointDto.CreateRequest request) {
        EntryPointDto.Response created = entryPointService.create(blockId, request, currentUser.id());
        return ResponseEntity.created(URI.create("/api/v1/entry-points/" + created.id())).body(created);
    }

    @GetMapping("/api/v1/entry-points/{entryPointId}")
    public EntryPointDto.Response get(@PathVariable UUID entryPointId) {
        return entryPointService.get(entryPointId, currentUser.id());
    }

    @PatchMapping("/api/v1/entry-points/{entryPointId}")
    public EntryPointDto.Response update(@PathVariable UUID entryPointId,
                                          @Valid @RequestBody EntryPointDto.UpdateRequest request) {
        return entryPointService.update(entryPointId, request, currentUser.id());
    }

    @DeleteMapping("/api/v1/entry-points/{entryPointId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID entryPointId,
                        @RequestParam(defaultValue = "false") boolean confirm) {
        entryPointService.delete(entryPointId, confirm, currentUser.id());
    }
}
