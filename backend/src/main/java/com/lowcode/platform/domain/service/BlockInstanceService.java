package com.lowcode.platform.domain.service;

import com.lowcode.platform.domain.api.BlockInstanceDto;
import com.lowcode.platform.domain.exception.EntityNotFoundException;
import com.lowcode.platform.domain.exception.ReferencedByScenariosException;
import com.lowcode.platform.domain.model.BlockInstance;
import com.lowcode.platform.domain.model.BlockType;
import com.lowcode.platform.domain.model.EntryPoint;
import com.lowcode.platform.domain.model.Scenario;
import com.lowcode.platform.domain.model.ScenarioStep;
import com.lowcode.platform.domain.model.Scheme;
import com.lowcode.platform.domain.repository.BlockInstanceRepository;
import com.lowcode.platform.domain.repository.BlockTypeRepository;
import com.lowcode.platform.domain.repository.EntryPointRepository;
import com.lowcode.platform.domain.repository.ScenarioRepository;
import com.lowcode.platform.domain.repository.ScenarioStepRepository;
import com.lowcode.platform.domain.repository.SchemeRepository;
import com.lowcode.platform.sharing.PermissionService;
import com.lowcode.platform.sharing.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BlockInstanceService {

    private final BlockInstanceRepository blockInstanceRepository;
    private final BlockTypeRepository blockTypeRepository;
    private final BlockTypeLookupService blockTypeLookupService;
    private final SchemeRepository schemeRepository;
    private final EntryPointRepository entryPointRepository;
    private final ScenarioRepository scenarioRepository;
    private final ScenarioStepRepository scenarioStepRepository;
    private final PermissionService permissionService;

    public BlockInstanceService(BlockInstanceRepository blockInstanceRepository,
                                 BlockTypeRepository blockTypeRepository,
                                 BlockTypeLookupService blockTypeLookupService,
                                 SchemeRepository schemeRepository,
                                 EntryPointRepository entryPointRepository,
                                 ScenarioRepository scenarioRepository,
                                 ScenarioStepRepository scenarioStepRepository,
                                 PermissionService permissionService) {
        this.blockInstanceRepository = blockInstanceRepository;
        this.blockTypeRepository = blockTypeRepository;
        this.blockTypeLookupService = blockTypeLookupService;
        this.schemeRepository = schemeRepository;
        this.entryPointRepository = entryPointRepository;
        this.scenarioRepository = scenarioRepository;
        this.scenarioStepRepository = scenarioStepRepository;
        this.permissionService = permissionService;
    }

    // Второе ревью CTO, ASAP-1 (BOLA): все пять методов ниже раньше принимали
    // projectId/blockId из URL и работали с сущностью без единой проверки
    // прав — см. javadoc PermissionService.requireOnProject. Read (list/get) —
    // READER; мутации (create/update/delete) — EDITOR.

    @Transactional(readOnly = true)
    public List<BlockInstanceDto.Response> listByProject(UUID projectId, UUID userId) {
        permissionService.requireOnProject(projectId, userId, Role.READER);
        Scheme scheme = findSchemeOrThrow(projectId);
        Map<UUID, BlockType> blockTypes = blockTypeLookupService.byId();
        return blockInstanceRepository.findBySchemeId(scheme.getId()).stream()
                .map(b -> toResponse(b, blockTypes))
                .toList();
    }

    @Transactional(readOnly = true)
    public BlockInstanceDto.Response get(UUID projectId, UUID blockId, UUID userId) {
        permissionService.requireOnProject(projectId, userId, Role.READER);
        Scheme scheme = findSchemeOrThrow(projectId);
        BlockInstance block = findBlockOrThrow(scheme.getId(), blockId);
        return toResponse(block, blockTypeLookupService.byId());
    }

    @Transactional
    public BlockInstanceDto.Response create(UUID projectId, BlockInstanceDto.CreateRequest request, UUID userId) {
        permissionService.requireOnProject(projectId, userId, Role.EDITOR);
        Scheme scheme = findSchemeOrThrow(projectId);
        BlockType blockType = blockTypeRepository.findById(request.blockTypeId())
                .orElseThrow(() -> new EntityNotFoundException("BlockType", request.blockTypeId()));

        BlockInstance block = new BlockInstance();
        block.setSchemeId(scheme.getId());
        block.setBlockTypeId(blockType.getId());
        block.setLabel(request.label());
        block.setX(request.x());
        block.setY(request.y());
        block = blockInstanceRepository.save(block);

        return toResponse(block, blockType);
    }

    @Transactional
    public BlockInstanceDto.Response update(UUID projectId, UUID blockId, BlockInstanceDto.UpdateRequest request,
                                              UUID userId) {
        permissionService.requireOnProject(projectId, userId, Role.EDITOR);
        Scheme scheme = findSchemeOrThrow(projectId);
        BlockInstance block = findBlockOrThrow(scheme.getId(), blockId);
        // PATCH: отсутствующее поле (null) — "не менять", см. комментарий в BlockInstanceDto.
        if (request.label() != null) {
            block.setLabel(request.label());
        }
        if (request.x() != null) {
            block.setX(request.x());
        }
        if (request.y() != null) {
            block.setY(request.y());
        }
        return toResponse(block, blockTypeLookupService.byId());
    }

    /**
     * См. api-contract.md "Решение: удаление блока с внешними ссылками":
     * если хоть один Entry Point блока используется как called_entry_point_id
     * в ScenarioStep (в т.ч. из чужого проекта) — без confirm=true бросаем 409
     * со списком затронутых сценариев.
     */
    @Transactional
    public void delete(UUID projectId, UUID blockId, boolean confirm, UUID userId) {
        permissionService.requireOnProject(projectId, userId, Role.EDITOR);
        Scheme scheme = findSchemeOrThrow(projectId);
        BlockInstance block = findBlockOrThrow(scheme.getId(), blockId);

        List<UUID> entryPointIds = entryPointRepository.findByBlockInstanceId(blockId).stream()
                .map(EntryPoint::getId)
                .toList();

        if (!confirm && !entryPointIds.isEmpty()) {
            List<Scenario> referencing = findReferencingScenarios(entryPointIds);
            if (!referencing.isEmpty()) {
                throw new ReferencedByScenariosException(
                        "Block is used by external scenarios; pass ?confirm=true to delete anyway", referencing);
            }
        }

        // Каскад в БД (on delete cascade) вычистит entry_point/connection,
        // но связи (connection) ссылаются на block_instance напрямую с cascade —
        // достаточно удалить сам блок.
        blockInstanceRepository.delete(block);
    }

    private List<Scenario> findReferencingScenarios(List<UUID> entryPointIds) {
        Map<UUID, Scenario> byId = new LinkedHashMap<>();

        // 1) Сценарии, шаги которых вызывают один из entry points блока
        for (ScenarioStep step : scenarioStepRepository.findByCalledEntryPointIdIn(entryPointIds)) {
            scenarioRepository.findById(step.getScenarioId()).ifPresent(s -> byId.put(s.getId(), s));
        }
        // 2) Сценарии, которые сами реализуют один из entry points блока
        for (Scenario scenario : scenarioRepository.findByEntryPointIdIn(entryPointIds)) {
            byId.put(scenario.getId(), scenario);
        }
        return List.copyOf(byId.values());
    }

    private Scheme findSchemeOrThrow(UUID projectId) {
        return schemeRepository.findByProjectId(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Scheme for project", projectId));
    }

    private BlockInstance findBlockOrThrow(UUID schemeId, UUID blockId) {
        return blockInstanceRepository.findByIdAndSchemeId(blockId, schemeId)
                .orElseThrow(() -> new EntityNotFoundException("BlockInstance", blockId));
    }

    private BlockInstanceDto.Response toResponse(BlockInstance block, Map<UUID, BlockType> blockTypes) {
        BlockType type = blockTypes.get(block.getBlockTypeId());
        return toResponse(block, type);
    }

    private BlockInstanceDto.Response toResponse(BlockInstance block, BlockType type) {
        return new BlockInstanceDto.Response(
                block.getId(), block.getSchemeId(), block.getBlockTypeId(),
                type != null ? type.getCode() : null,
                block.getLabel(), block.getX(), block.getY());
    }
}
