package com.lowcode.platform.domain.service;

import com.lowcode.platform.domain.api.ScenarioDto;
import com.lowcode.platform.domain.exception.EntityNotFoundException;
import com.lowcode.platform.domain.model.EntryPoint;
import com.lowcode.platform.domain.model.Scenario;
import com.lowcode.platform.domain.repository.EntryPointRepository;
import com.lowcode.platform.domain.repository.ScenarioRepository;
import com.lowcode.platform.sharing.CollaboratorService;
import com.lowcode.platform.sharing.PermissionService;
import com.lowcode.platform.sharing.Role;
import com.lowcode.platform.versioning.VersioningService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ScenarioService {

    private final ScenarioRepository scenarioRepository;
    private final EntryPointRepository entryPointRepository;
    private final CollaboratorService collaboratorService;
    private final VersioningService versioningService;
    private final PermissionService permissionService;

    public ScenarioService(ScenarioRepository scenarioRepository, EntryPointRepository entryPointRepository,
                            CollaboratorService collaboratorService, VersioningService versioningService,
                            PermissionService permissionService) {
        this.scenarioRepository = scenarioRepository;
        this.entryPointRepository = entryPointRepository;
        this.collaboratorService = collaboratorService;
        this.versioningService = versioningService;
        this.permissionService = permissionService;
    }

    @Transactional(readOnly = true)
    public Page<ScenarioDto.Response> list(UUID userId, Pageable pageable) {
        // Ревью CTO, п.1.3: раньше отдавал ВСЕ сценарии в базе.
        return scenarioRepository.findAllVisibleTo(userId, pageable).map(this::toResponse);
    }

    /** Второе ревью CTO, ASAP-1 (BOLA) — см. javadoc PermissionService.requireOnScenario. */
    @Transactional(readOnly = true)
    public ScenarioDto.Response get(UUID scenarioId, UUID userId) {
        permissionService.requireOnScenario(scenarioId, userId, Role.READER);
        return toResponse(findOrThrow(scenarioId));
    }

    /**
     * Сценарий — реализация ровно одного Entry Point (unique constraint в БД);
     * проверяем занятость явно, чтобы вернуть внятный 409 вместо голого
     * DataIntegrityViolationException из-за unique constraint.
     * ownerId — из SecurityContext на уровне контроллера, не тело запроса
     * (см. ревью CTO, п.1.2).
     */
    @Transactional
    public ScenarioDto.Response create(ScenarioDto.CreateRequest request, UUID ownerId) {
        EntryPoint entryPoint = entryPointRepository.findById(request.entryPointId())
                .orElseThrow(() -> new EntityNotFoundException("EntryPoint", request.entryPointId()));

        scenarioRepository.findByEntryPointId(entryPoint.getId()).ifPresent(existing -> {
            throw new IllegalStateException(
                    "Entry point " + entryPoint.getId() + " already has a scenario: " + existing.getId());
        });

        Scenario scenario = new Scenario();
        scenario.setName(request.name());
        scenario.setEntryPointId(entryPoint.getId());
        scenario.setOwnerId(ownerId);
        scenario = scenarioRepository.save(scenario);

        // UC9: создатель сценария автоматически становится Owner (единственное
        // место, где выдаётся эта роль — см. CollaboratorService.grantOwner).
        collaboratorService.grantOwner(scenario.getId(), ownerId);
        // UC8: версия 1 — начальное состояние (ещё без шагов). Дальше версия
        // создаётся на каждую мутацию сценария/шагов, см. VersioningService.
        versioningService.snapshot(scenario.getId());

        return toResponse(scenario);
    }

    @Transactional
    public ScenarioDto.Response update(UUID scenarioId, ScenarioDto.UpdateRequest request, UUID userId) {
        permissionService.requireOnScenario(scenarioId, userId, Role.EDITOR);
        Scenario scenario = findOrThrow(scenarioId);
        if (request.name() != null) {
            scenario.setName(request.name());
        }
        versioningService.snapshot(scenarioId);
        return toResponse(scenario);
    }

    /** OWNER — уже задокументировано в api-contract.md ("DELETE — только Owner"), теперь и проверяется. */
    @Transactional
    public void delete(UUID scenarioId, UUID userId) {
        permissionService.requireOnScenario(scenarioId, userId, Role.OWNER);
        Scenario scenario = findOrThrow(scenarioId);
        // scenario_step / collaborator / scenario_version удаляются каскадно (on delete cascade, V1__init.sql).
        // Внешние ScenarioStep.called_entry_point_id, ссылающиеся НА entry point этого сценария,
        // не затрагиваются: entry point просто становится листом дерева вызовов (валидное состояние).
        scenarioRepository.delete(scenario);
    }

    private Scenario findOrThrow(UUID scenarioId) {
        return scenarioRepository.findById(scenarioId)
                .orElseThrow(() -> new EntityNotFoundException("Scenario", scenarioId));
    }

    private ScenarioDto.Response toResponse(Scenario s) {
        return new ScenarioDto.Response(s.getId(), s.getName(), s.getEntryPointId(), s.getOwnerId());
    }
}
