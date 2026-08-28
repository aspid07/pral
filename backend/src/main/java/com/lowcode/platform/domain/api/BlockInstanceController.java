package com.lowcode.platform.domain.api;

import com.lowcode.platform.auth.CurrentUser;
import com.lowcode.platform.domain.service.BlockInstanceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/** См. api-contract.md, раздел "REST: Блоки и Entry Point". */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/blocks")
public class BlockInstanceController {

    private final BlockInstanceService blockInstanceService;
    private final CurrentUser currentUser;

    public BlockInstanceController(BlockInstanceService blockInstanceService, CurrentUser currentUser) {
        this.blockInstanceService = blockInstanceService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<BlockInstanceDto.Response> list(@PathVariable UUID projectId) {
        return blockInstanceService.listByProject(projectId, currentUser.id());
    }

    @PostMapping
    public ResponseEntity<BlockInstanceDto.Response> create(@PathVariable UUID projectId,
                                                              @Valid @RequestBody BlockInstanceDto.CreateRequest request) {
        BlockInstanceDto.Response created = blockInstanceService.create(projectId, request, currentUser.id());
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/blocks/" + created.id()))
                .body(created);
    }

    @GetMapping("/{blockId}")
    public BlockInstanceDto.Response get(@PathVariable UUID projectId, @PathVariable UUID blockId) {
        return blockInstanceService.get(projectId, blockId, currentUser.id());
    }

    @PatchMapping("/{blockId}")
    public BlockInstanceDto.Response update(@PathVariable UUID projectId, @PathVariable UUID blockId,
                                             @Valid @RequestBody BlockInstanceDto.UpdateRequest request) {
        return blockInstanceService.update(projectId, blockId, request, currentUser.id());
    }

    @DeleteMapping("/{blockId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID projectId, @PathVariable UUID blockId,
                        @RequestParam(defaultValue = "false") boolean confirm) {
        blockInstanceService.delete(projectId, blockId, confirm, currentUser.id());
    }
}
