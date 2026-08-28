package com.lowcode.platform.domain.service;

import com.lowcode.platform.domain.api.BlockInstanceDto;
import com.lowcode.platform.domain.api.ConnectionDto;
import com.lowcode.platform.domain.api.EntryPointDto;
import com.lowcode.platform.domain.api.SchemeDto;
import com.lowcode.platform.domain.exception.EntityNotFoundException;
import com.lowcode.platform.domain.model.BlockInstance;
import com.lowcode.platform.domain.model.BlockType;
import com.lowcode.platform.domain.model.Scheme;
import com.lowcode.platform.domain.repository.BlockInstanceRepository;
import com.lowcode.platform.domain.repository.ConnectionRepository;
import com.lowcode.platform.domain.repository.EntryPointRepository;
import com.lowcode.platform.domain.repository.SchemeRepository;
import com.lowcode.platform.sharing.PermissionService;
import com.lowcode.platform.sharing.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Отдельный от BlockInstanceService/ConnectionService сервис "только для чтения",
 *  т.к. GET /projects/{id}/scheme отдаёт блоки, связи и entry points одним
 *  ответом — фронтенду в run mode нужна вся эта информация сразу, чтобы по
 *  targetEntryPointId из STEP_STARTED найти блок для подсветки на канвасе. */
@Service
public class SchemeService {

    private final SchemeRepository schemeRepository;
    private final BlockInstanceRepository blockInstanceRepository;
    private final ConnectionRepository connectionRepository;
    private final BlockTypeLookupService blockTypeLookupService;
    private final EntryPointRepository entryPointRepository;
    private final PermissionService permissionService;

    public SchemeService(SchemeRepository schemeRepository,
                          BlockInstanceRepository blockInstanceRepository,
                          ConnectionRepository connectionRepository,
                          BlockTypeLookupService blockTypeLookupService,
                          EntryPointRepository entryPointRepository,
                          PermissionService permissionService) {
        this.schemeRepository = schemeRepository;
        this.blockInstanceRepository = blockInstanceRepository;
        this.connectionRepository = connectionRepository;
        this.blockTypeLookupService = blockTypeLookupService;
        this.entryPointRepository = entryPointRepository;
        this.permissionService = permissionService;
    }

    /** Второе ревью CTO, ASAP-1 (BOLA) — см. javadoc PermissionService.requireOnProject. */
    @Transactional(readOnly = true)
    public SchemeDto.Response getByProject(UUID projectId, UUID userId) {
        permissionService.requireOnProject(projectId, userId, Role.READER);

        Scheme scheme = schemeRepository.findByProjectId(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Scheme for project", projectId));

        Map<UUID, BlockType> blockTypes = blockTypeLookupService.byId();

        List<BlockInstance> blockInstances = blockInstanceRepository.findBySchemeId(scheme.getId());

        var blocks = blockInstances.stream()
                .map(b -> {
                    BlockType type = blockTypes.get(b.getBlockTypeId());
                    return new BlockInstanceDto.Response(
                            b.getId(), b.getSchemeId(), b.getBlockTypeId(),
                            type != null ? type.getCode() : null,
                            b.getLabel(), b.getX(), b.getY());
                })
                .toList();

        var connections = connectionRepository.findBySchemeId(scheme.getId()).stream()
                .map(c -> new ConnectionDto.Response(
                        c.getId(), c.getSchemeId(), c.getSourceBlockId(), c.getTargetBlockId(),
                        c.getIntegrationType(), c.isExternal()))
                .toList();

        List<UUID> blockIds = blockInstances.stream().map(BlockInstance::getId).toList();
        var entryPoints = entryPointRepository.findByBlockInstanceIdIn(blockIds).stream()
                .map(ep -> new EntryPointDto.Response(ep.getId(), ep.getBlockInstanceId(), ep.getName(), ep.getKind()))
                .toList();

        return new SchemeDto.Response(scheme.getId(), projectId, blocks, connections, entryPoints);
    }
}
