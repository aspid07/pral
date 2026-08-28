package com.lowcode.platform.sharing;

import com.lowcode.platform.domain.exception.EntityNotFoundException;
import com.lowcode.platform.domain.model.Scenario;
import com.lowcode.platform.domain.repository.ScenarioRepository;
import com.lowcode.platform.domain.service.EntryPointProjectResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Stage 3 плана auth/ролей: гибридная модель — Project-роль (грубо) и
 * Scenario-роль (точечно) — независимые источники, эффективная роль на
 * сценарии = max() из обоих.
 *
 * Второе ревью CTO, находки ASAP-1/ASAP-2 (BOLA): effectiveRoleOn*/hasAtLeastOn*
 * ВЫЧИСЛЯЛИ роль корректно и были покрыты тестами, но ни один контроллер их не
 * ВЫЗЫВАЛ — доступ по прямому id (GET/PATCH/DELETE) не проверял вообще ничего,
 * кроме валидного JWT. requireOnProject/requireOnScenario ниже — единая точка
 * принуждения (см. рекомендацию ревью, пункт MEDIUM-6): каждый *Service теперь
 * ОБЯЗАН принять userId и вызвать guard первым делом в get/update/delete —
 * сигнатура физически не даёт его забыть (тот же принцип, что уже был у
 * create(), куда ownerId/userId уже прокидывался).
 */
@Service
public class PermissionService {

    private final CollaboratorRepository collaboratorRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ScenarioRepository scenarioRepository;
    private final EntryPointProjectResolver projectResolver;

    public PermissionService(CollaboratorRepository collaboratorRepository,
                              ProjectMemberRepository projectMemberRepository,
                              ScenarioRepository scenarioRepository,
                              EntryPointProjectResolver projectResolver) {
        this.collaboratorRepository = collaboratorRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.scenarioRepository = scenarioRepository;
        this.projectResolver = projectResolver;
    }

    @Transactional(readOnly = true)
    public Optional<Role> effectiveRoleOnProject(UUID projectId, UUID userId) {
        return projectMemberRepository.findByProjectIdAndUserId(projectId, userId).map(ProjectMember::getRole);
    }

    /**
     * max(роль на домашнем проекте сценария, точечная роль на самом сценарии).
     * "Домашний проект" — тот, чей entry point сценарий реализует (та же
     * цепочка, что использует ExecutionEngine для CLUSTER_ENTERED).
     */
    @Transactional(readOnly = true)
    public Optional<Role> effectiveRoleOnScenario(UUID scenarioId, UUID userId) {
        Scenario scenario = scenarioRepository.findById(scenarioId)
                .orElseThrow(() -> new EntityNotFoundException("Scenario", scenarioId));

        Optional<Role> scenarioRole = collaboratorRepository.findByScenarioIdAndUserId(scenarioId, userId)
                .map(Collaborator::getRole);

        UUID homeProjectId = projectResolver.resolveProjectId(scenario.getEntryPointId());
        Optional<Role> projectRole = effectiveRoleOnProject(homeProjectId, userId);

        return higherOf(scenarioRole, projectRole);
    }

    public boolean hasAtLeastOnProject(UUID projectId, UUID userId, Role minimum) {
        return effectiveRoleOnProject(projectId, userId).map(r -> r.atLeast(minimum)).orElse(false);
    }

    public boolean hasAtLeastOnScenario(UUID scenarioId, UUID userId, Role minimum) {
        return effectiveRoleOnScenario(scenarioId, userId).map(r -> r.atLeast(minimum)).orElse(false);
    }

    /**
     * Бросает {@link EntityNotFoundException} (404), НЕ 403, если у userId
     * недостаточно прав на projectId. Осознанный выбор кода ответа: 403
     * подтвердил бы, что ресурс с таким id вообще существует ("оракул
     * существования" — тот же класс проблемы, что уже решён для email при
     * регистрации, см. AuthService.register) — 404 неотличим от "такого
     * проекта нет вообще". PermissionService — единственное место, откуда
     * это решение принимается, так что все вызывающие сервисы согласованы
     * автоматически, без риска, что где-то забудут и вернут 403.
     */
    public void requireOnProject(UUID projectId, UUID userId, Role minimum) {
        if (!hasAtLeastOnProject(projectId, userId, minimum)) {
            throw new EntityNotFoundException("Project", projectId);
        }
    }

    /** Та же логика (404, не 403), что и {@link #requireOnProject} — см. его javadoc. */
    public void requireOnScenario(UUID scenarioId, UUID userId, Role minimum) {
        if (!hasAtLeastOnScenario(scenarioId, userId, minimum)) {
            throw new EntityNotFoundException("Scenario", scenarioId);
        }
    }

    private Optional<Role> higherOf(Optional<Role> a, Optional<Role> b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        return Optional.of(a.get().atLeast(b.get()) ? a.get() : b.get());
    }
}
