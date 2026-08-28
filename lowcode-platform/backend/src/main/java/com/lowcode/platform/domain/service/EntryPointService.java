package com.lowcode.platform.domain.service;

import com.lowcode.platform.domain.api.EntryPointDto;
import com.lowcode.platform.domain.exception.EntityNotFoundException;
import com.lowcode.platform.domain.exception.ReferencedByScenariosException;
import com.lowcode.platform.domain.model.BlockInstance;
import com.lowcode.platform.domain.model.EntryPoint;
import com.lowcode.platform.domain.model.Scenario;
import com.lowcode.platform.domain.model.ScenarioStep;
import com.lowcode.platform.domain.model.Scheme;
import com.lowcode.platform.domain.repository.BlockInstanceRepository;
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
public class EntryPointService {

    private final EntryPointRepository entryPointRepository;
    private final BlockInstanceRepository blockInstanceRepository;
    private final SchemeRepository schemeRepository;
    private final ScenarioRepository scenarioRepository;
    private final ScenarioStepRepository scenarioStepRepository;
    private final EntryPointProjectResolver projectResolver;
    private final PermissionService permissionService;

    public EntryPointService(EntryPointRepository entryPointRepository,
                              BlockInstanceRepository blockInstanceRepository,
                              SchemeRepository schemeRepository,
                              ScenarioRepository scenarioRepository,
                              ScenarioStepRepository scenarioStepRepository,
                              EntryPointProjectResolver projectResolver,
                              PermissionService permissionService) {
        this.entryPointRepository = entryPointRepository;
        this.blockInstanceRepository = blockInstanceRepository;
        this.schemeRepository = schemeRepository;
        this.scenarioRepository = scenarioRepository;
        this.scenarioStepRepository = scenarioStepRepository;
        this.projectResolver = projectResolver;
        this.permissionService = permissionService;
    }

    /**
     * Второе ревью CTO, ASAP-1 (BOLA). EntryPoint глобально адресуем (см.
     * class-javadoc EntryPointController) и МОЖЕТ вызываться сценариями из
     * чужих проектов (см. ExecutionEngine, kind=external) — но операции
     * ЗДЕСЬ (получить/изменить/удалить сам entry point) относятся к его
     * СОБСТВЕННОМУ ("домашнему") проекту, не к проекту вызывающего сценария.
     * requireOnProject проверяется именно на нём — глобальная адресуемость
     * даёт право ССЫЛАТЬСЯ на чужой entry point из CALL-шага, но не право
     * редактировать/удалить его.
     */
    @Transactional(readOnly = true)
    public EntryPointDto.Response get(UUID entryPointId, UUID userId) {
        permissionService.requireOnProject(projectResolver.resolveProjectId(entryPointId), userId, Role.READER);
        return toResponse(findOrThrow(entryPointId));
    }

    @Transactional(readOnly = true)
    public List<EntryPointDto.Response> listByBlock(UUID blockId, UUID userId) {
        permissionService.requireOnProject(projectIdOfBlock(blockId), userId, Role.READER);
        return entryPointRepository.findByBlockInstanceId(blockId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public EntryPointDto.Response create(UUID blockId, EntryPointDto.CreateRequest request, UUID userId) {
        permissionService.requireOnProject(projectIdOfBlock(blockId), userId, Role.EDITOR);
        BlockInstance block = blockInstanceRepository.findById(blockId)
                .orElseThrow(() -> new EntityNotFoundException("BlockInstance", blockId));

        EntryPoint entryPoint = new EntryPoint();
        entryPoint.setBlockInstanceId(block.getId());
        entryPoint.setName(request.name());
        entryPoint.setKind(request.kind());
        entryPoint = entryPointRepository.save(entryPoint);

        return toResponse(entryPoint);
    }

    @Transactional
    public EntryPointDto.Response update(UUID entryPointId, EntryPointDto.UpdateRequest request, UUID userId) {
        permissionService.requireOnProject(projectResolver.resolveProjectId(entryPointId), userId, Role.EDITOR);
        EntryPoint entryPoint = findOrThrow(entryPointId);
        // PATCH: отсутствующее поле (null) — "не менять".
        if (request.name() != null) {
            entryPoint.setName(request.name());
        }
        if (request.kind() != null) {
            entryPoint.setKind(request.kind());
        }
        return toResponse(entryPoint);
    }

    /**
     * Тот же принцип защиты, что и для удаления блока (api-contract.md): Entry
     * Point глобально адресуем, на него может ссылаться Scenario (реализующая
     * его) или ScenarioStep (вызывающий его) из любого проекта.
     */
    @Transactional
    public void delete(UUID entryPointId, boolean confirm, UUID userId) {
        permissionService.requireOnProject(projectResolver.resolveProjectId(entryPointId), userId, Role.EDITOR);
        EntryPoint entryPoint = findOrThrow(entryPointId);

        if (!confirm) {
            List<Scenario> referencing = findReferencingScenarios(entryPointId);
            if (!referencing.isEmpty()) {
                throw new ReferencedByScenariosException(
                        "Entry point is used by scenarios; pass ?confirm=true to delete anyway", referencing);
            }
        }

        entryPointRepository.delete(entryPoint);
    }

    private UUID projectIdOfBlock(UUID blockId) {
        BlockInstance block = blockInstanceRepository.findById(blockId)
                .orElseThrow(() -> new EntityNotFoundException("BlockInstance", blockId));
        Scheme scheme = schemeRepository.findById(block.getSchemeId())
                .orElseThrow(() -> new EntityNotFoundException("Scheme", block.getSchemeId()));
        return scheme.getProjectId();
    }

    private List<Scenario> findReferencingScenarios(UUID entryPointId) {
        Map<UUID, Scenario> byId = new LinkedHashMap<>();
        for (ScenarioStep step : scenarioStepRepository.findByCalledEntryPointIdIn(List.of(entryPointId))) {
            scenarioRepository.findById(step.getScenarioId()).ifPresent(s -> byId.put(s.getId(), s));
        }
        scenarioRepository.findByEntryPointId(entryPointId).ifPresent(s -> byId.put(s.getId(), s));
        return List.copyOf(byId.values());
    }

    private EntryPoint findOrThrow(UUID entryPointId) {
        return entryPointRepository.findById(entryPointId)
                .orElseThrow(() -> new EntityNotFoundException("EntryPoint", entryPointId));
    }

    private EntryPointDto.Response toResponse(EntryPoint e) {
        return new EntryPointDto.Response(e.getId(), e.getBlockInstanceId(), e.getName(), e.getKind());
    }
}
