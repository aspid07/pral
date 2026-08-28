package com.lowcode.platform.domain.service;

import com.lowcode.platform.domain.api.ScenarioStepDto;
import com.lowcode.platform.domain.exception.EntityNotFoundException;
import com.lowcode.platform.domain.model.Scenario;
import com.lowcode.platform.domain.model.ScenarioStep;
import com.lowcode.platform.domain.repository.EntryPointRepository;
import com.lowcode.platform.domain.repository.ScenarioRepository;
import com.lowcode.platform.domain.repository.ScenarioStepRepository;
import com.lowcode.platform.sharing.PermissionService;
import com.lowcode.platform.sharing.Role;
import com.lowcode.platform.versioning.VersioningService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ScenarioStepService {

    private final ScenarioStepRepository scenarioStepRepository;
    private final ScenarioRepository scenarioRepository;
    private final EntryPointRepository entryPointRepository;
    private final VersioningService versioningService;
    private final PermissionService permissionService;

    public ScenarioStepService(ScenarioStepRepository scenarioStepRepository,
                                ScenarioRepository scenarioRepository,
                                EntryPointRepository entryPointRepository,
                                VersioningService versioningService,
                                PermissionService permissionService) {
        this.scenarioStepRepository = scenarioStepRepository;
        this.scenarioRepository = scenarioRepository;
        this.entryPointRepository = entryPointRepository;
        this.versioningService = versioningService;
        this.permissionService = permissionService;
    }

    /** Второе ревью CTO, ASAP-1 (BOLA) — см. javadoc PermissionService.requireOnScenario. */
    @Transactional(readOnly = true)
    public List<ScenarioStepDto.Response> list(UUID scenarioId, UUID userId) {
        permissionService.requireOnScenario(scenarioId, userId, Role.READER);
        requireScenario(scenarioId);
        return scenarioStepRepository.findByScenarioIdOrderByOrderIndexAsc(scenarioId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * CALL — лист/вызов, обязательно ссылается на calledEntryPointId.
     * ALT/PARALLEL — управляющие узлы-обёртки без собственных параметров.
     * RETRY — обязателен maxAttempts (сколько попыток анимировать в симуляции).
     * TIMEOUT — обязателен timeoutMs (бюджет, публикуется как метаданные).
     * См. ExecutionEngine и комментарий в модели ScenarioStep.
     * orderIndex вычисляется как следующий свободный среди шагов с тем же
     * parentStepId — клиент им не управляет напрямую.
     */
    @Transactional
    public ScenarioStepDto.Response create(UUID scenarioId, ScenarioStepDto.CreateRequest request, UUID userId) {
        permissionService.requireOnScenario(scenarioId, userId, Role.EDITOR);
        requireScenario(scenarioId);

        if (request.parentStepId() != null) {
            findStepInScenarioOrThrow(scenarioId, request.parentStepId());
        }

        validateStepFields(request.stepType(), request.calledEntryPointId(),
                request.maxAttempts(), request.timeoutMs());

        // Гонка при параллельном создании соседних шагов возможна (read-then-write) —
        // от порчи данных защищает unique-индекс scenario_step_sibling_order_idx
        // (V5), проигравший запрос получит честный 409 от ApiExceptionHandler,
        // а не тихий дубль order_index. Ретрай — на клиенте (см. ревью).
        int nextOrderIndex = scenarioStepRepository
                .findByScenarioIdAndParentStepId(scenarioId, request.parentStepId())
                .stream()
                .mapToInt(ScenarioStep::getOrderIndex)
                .max()
                .orElse(-1) + 1;

        ScenarioStep step = new ScenarioStep();
        step.setScenarioId(scenarioId);
        step.setParentStepId(request.parentStepId());
        step.setStepType(request.stepType());
        step.setCalledEntryPointId(request.calledEntryPointId());
        step.setConditionLabel(request.conditionLabel());
        step.setParallelGroupId(request.parallelGroupId());
        step.setMaxAttempts(request.maxAttempts());
        step.setTimeoutMs(request.timeoutMs());
        step.setOrderIndex(nextOrderIndex);
        step = scenarioStepRepository.save(step);
        versioningService.snapshot(scenarioId);

        return toResponse(step);
    }

    @Transactional
    // PATCH: поле, отсутствующее в запросе (null), означает "не менять" — берём
    // текущее значение шага (см. также BlockInstanceDto.UpdateRequest). Раньше
    // это безусловно перезаписывало поля значением из запроса: PATCH одного
    // conditionLabel без calledEntryPointId у CALL-шага падал на валидации
    // (CALL требует непустой calledEntryPointId), заставляя каждый раз
    // пересылать весь объект. Обратная сторона: conditionLabel/parallelGroupId
    // теперь нельзя явно очистить через PATCH — только заменить непустым
    // значением; для MVP это приемлемое упрощение (без отдельного wrapper-типа
    // под "поле явно передано как null" vs "поле отсутствует").
    public ScenarioStepDto.Response update(UUID scenarioId, UUID stepId, ScenarioStepDto.UpdateRequest request,
                                            UUID userId) {
        permissionService.requireOnScenario(scenarioId, userId, Role.EDITOR);
        ScenarioStep step = findStepInScenarioOrThrow(scenarioId, stepId);

        UUID calledEntryPointId = request.calledEntryPointId() != null
                ? request.calledEntryPointId() : step.getCalledEntryPointId();
        String conditionLabel = request.conditionLabel() != null
                ? request.conditionLabel() : step.getConditionLabel();
        String parallelGroupId = request.parallelGroupId() != null
                ? request.parallelGroupId() : step.getParallelGroupId();
        Integer maxAttempts = request.maxAttempts() != null ? request.maxAttempts() : step.getMaxAttempts();
        Integer timeoutMs = request.timeoutMs() != null ? request.timeoutMs() : step.getTimeoutMs();

        validateStepFields(step.getStepType(), calledEntryPointId, maxAttempts, timeoutMs);

        step.setCalledEntryPointId(calledEntryPointId);
        step.setConditionLabel(conditionLabel);
        step.setParallelGroupId(parallelGroupId);
        step.setMaxAttempts(maxAttempts);
        step.setTimeoutMs(timeoutMs);
        versioningService.snapshot(scenarioId);
        return toResponse(step);
    }

    /**
     * parent_step_id — self-FK без on delete cascade (V1__init.sql), поэтому
     * при удалении узла-обёртки (ALT/PARALLEL/...) нужно вручную рекурсивно
     * снести вложенные шаги, иначе БД отклонит запрос ссылочной целостностью.
     */
    @Transactional
    public void delete(UUID scenarioId, UUID stepId, UUID userId) {
        permissionService.requireOnScenario(scenarioId, userId, Role.EDITOR);
        ScenarioStep step = findStepInScenarioOrThrow(scenarioId, stepId);
        deleteRecursively(step);
        versioningService.snapshot(scenarioId);
    }

    private void deleteRecursively(ScenarioStep step) {
        for (ScenarioStep child : scenarioStepRepository.findByParentStepId(step.getId())) {
            deleteRecursively(child);
        }
        scenarioStepRepository.delete(step);
    }

    private void validateStepFields(ScenarioStep.StepType stepType, UUID calledEntryPointId,
                                     Integer maxAttempts, Integer timeoutMs) {
        boolean isCall = stepType == ScenarioStep.StepType.CALL;
        if (isCall && calledEntryPointId == null) {
            throw new IllegalArgumentException("CALL step requires calledEntryPointId");
        }
        if (!isCall && calledEntryPointId != null) {
            throw new IllegalArgumentException(stepType + " is a wrapper node and must not set calledEntryPointId");
        }
        if (calledEntryPointId != null && !entryPointRepository.existsById(calledEntryPointId)) {
            // Защиту от циклов вызовов (Entry Point → Scenario → шаг, вызывающий тот же
            // Entry Point) сознательно оставляем ExecutionEngine — CRUD-слой её не видит,
            // цикл может появиться позже, если кто-то создаст встречный сценарий.
            throw new EntityNotFoundException("EntryPoint", calledEntryPointId);
        }

        boolean isRetry = stepType == ScenarioStep.StepType.RETRY;
        if (isRetry && (maxAttempts == null || maxAttempts < 2)) {
            throw new IllegalArgumentException("RETRY step requires maxAttempts >= 2");
        }
        if (!isRetry && maxAttempts != null) {
            throw new IllegalArgumentException(stepType + " must not set maxAttempts (RETRY-only field)");
        }

        boolean isTimeout = stepType == ScenarioStep.StepType.TIMEOUT;
        if (isTimeout && (timeoutMs == null || timeoutMs < 1)) {
            throw new IllegalArgumentException("TIMEOUT step requires timeoutMs >= 1");
        }
        if (!isTimeout && timeoutMs != null) {
            throw new IllegalArgumentException(stepType + " must not set timeoutMs (TIMEOUT-only field)");
        }
    }

    private Scenario requireScenario(UUID scenarioId) {
        return scenarioRepository.findById(scenarioId)
                .orElseThrow(() -> new EntityNotFoundException("Scenario", scenarioId));
    }

    private ScenarioStep findStepInScenarioOrThrow(UUID scenarioId, UUID stepId) {
        ScenarioStep step = scenarioStepRepository.findById(stepId)
                .orElseThrow(() -> new EntityNotFoundException("ScenarioStep", stepId));
        if (!step.getScenarioId().equals(scenarioId)) {
            throw new EntityNotFoundException("ScenarioStep", stepId);
        }
        return step;
    }

    private ScenarioStepDto.Response toResponse(ScenarioStep s) {
        return new ScenarioStepDto.Response(
                s.getId(), s.getScenarioId(), s.getOrderIndex(), s.getParentStepId(),
                s.getStepType(), s.getCalledEntryPointId(), s.getConditionLabel(), s.getParallelGroupId(),
                s.getMaxAttempts(), s.getTimeoutMs());
    }
}
