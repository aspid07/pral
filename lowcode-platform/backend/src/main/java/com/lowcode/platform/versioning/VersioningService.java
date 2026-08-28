package com.lowcode.platform.versioning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowcode.platform.domain.api.ScenarioDto;
import com.lowcode.platform.domain.api.ScenarioStepDto;
import com.lowcode.platform.domain.exception.EntityNotFoundException;
import com.lowcode.platform.domain.model.Scenario;
import com.lowcode.platform.domain.model.ScenarioStep;
import com.lowcode.platform.domain.repository.ScenarioRepository;
import com.lowcode.platform.domain.repository.ScenarioStepRepository;
import com.lowcode.platform.sharing.PermissionService;
import com.lowcode.platform.sharing.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * UC8 (Should, functional-requirements.md): версионирование — "не только
 * текущий AS-IS". В api-contract.md нет отдельного POST .../versions (только
 * GET-список и GET одной версии) — версия создаётся автоматически на каждую
 * мутацию (Scenario.update, ScenarioStep create/update/delete), а не по
 * ручному "сохранить версию". Снэпшоты, не дельты (см. javadoc
 * ScenarioVersion) — проще инвалидация и чтение произвольной версии, дороже
 * по месту на диске — приемлемо для MVP.
 */
@Service
public class VersioningService {

    private final ScenarioVersionRepository versionRepository;
    private final ScenarioRepository scenarioRepository;
    private final ScenarioStepRepository scenarioStepRepository;
    private final ObjectMapper objectMapper;
    private final PermissionService permissionService;

    public VersioningService(ScenarioVersionRepository versionRepository,
                              ScenarioRepository scenarioRepository,
                              ScenarioStepRepository scenarioStepRepository,
                              ObjectMapper objectMapper,
                              PermissionService permissionService) {
        this.versionRepository = versionRepository;
        this.scenarioRepository = scenarioRepository;
        this.scenarioStepRepository = scenarioStepRepository;
        this.objectMapper = objectMapper;
        this.permissionService = permissionService;
    }

    /**
     * Вызывается из ScenarioService/ScenarioStepService после каждой успешной
     * мутации — БЕЗ userId и без своей проверки прав: авторизация уже
     * произошла на уровне вызывающего сервиса (тот сам проверил EDITOR на
     * scenarioId, прежде чем дойти до этой точки) — снова проверять здесь
     * было бы избыточно, а не дополнительной защитой (userId туда даже не
     * пробрасывается сквозь стек вызовов). list()/get() ниже — другое дело,
     * это отдельные, напрямую вызываемые из VersionController эндпоинты.
     */
    @Transactional
    public void snapshot(UUID scenarioId) {
        Scenario scenario = scenarioRepository.findById(scenarioId)
                .orElseThrow(() -> new EntityNotFoundException("Scenario", scenarioId));
        List<ScenarioStep> steps = scenarioStepRepository.findByScenarioIdOrderByOrderIndexAsc(scenarioId);

        VersionDto.Snapshot snapshot = new VersionDto.Snapshot(
                new ScenarioDto.Response(scenario.getId(), scenario.getName(),
                        scenario.getEntryPointId(), scenario.getOwnerId()),
                steps.stream().map(this::toStepResponse).toList());

        ScenarioVersion version = new ScenarioVersion();
        version.setScenarioId(scenarioId);
        version.setVersionNumber((int) versionRepository.countByScenarioId(scenarioId) + 1);
        version.setSnapshotJson(serialize(snapshot));
        versionRepository.save(version);
    }

    /** Второе ревью CTO, ASAP-1 (BOLA) — история изменений чужого сценария целиком, см. javadoc PermissionService.requireOnScenario. */
    @Transactional(readOnly = true)
    public List<VersionDto.Summary> list(UUID scenarioId, UUID userId) {
        permissionService.requireOnScenario(scenarioId, userId, Role.READER);
        requireScenario(scenarioId);
        return versionRepository.findByScenarioIdOrderByVersionNumberAsc(scenarioId).stream()
                .map(v -> new VersionDto.Summary(v.getId(), v.getVersionNumber(), v.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public VersionDto.Detail get(UUID scenarioId, UUID versionId, UUID userId) {
        permissionService.requireOnScenario(scenarioId, userId, Role.READER);
        requireScenario(scenarioId);
        ScenarioVersion version = versionRepository.findByIdAndScenarioId(versionId, scenarioId)
                .orElseThrow(() -> new EntityNotFoundException("ScenarioVersion", versionId));
        VersionDto.Snapshot snapshot = deserialize(version.getSnapshotJson());
        return new VersionDto.Detail(version.getId(), version.getVersionNumber(), version.getCreatedAt(), snapshot);
    }

    private String serialize(VersionDto.Snapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            // Сериализация DTO из простых полей (UUID/String/int/enum) практически
            // никогда не должна падать; заворачиваем в unchecked, чтобы не тащить
            // checked-исключение через сигнатуры ScenarioService/ScenarioStepService.
            throw new IllegalStateException("Failed to serialize scenario snapshot for " + snapshot.scenario().id(), e);
        }
    }

    private VersionDto.Snapshot deserialize(String json) {
        try {
            return objectMapper.readValue(json, VersionDto.Snapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize scenario snapshot", e);
        }
    }

    private void requireScenario(UUID scenarioId) {
        if (!scenarioRepository.existsById(scenarioId)) {
            throw new EntityNotFoundException("Scenario", scenarioId);
        }
    }

    private ScenarioStepDto.Response toStepResponse(ScenarioStep s) {
        return new ScenarioStepDto.Response(
                s.getId(), s.getScenarioId(), s.getOrderIndex(), s.getParentStepId(),
                s.getStepType(), s.getCalledEntryPointId(), s.getConditionLabel(), s.getParallelGroupId(),
                s.getMaxAttempts(), s.getTimeoutMs());
    }
}
