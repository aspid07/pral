package com.lowcode.platform.domain.repository;

import com.lowcode.platform.domain.model.ScenarioStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ScenarioStepRepository extends JpaRepository<ScenarioStep, UUID> {
    List<ScenarioStep> findByScenarioIdOrderByOrderIndexAsc(UUID scenarioId);

    List<ScenarioStep> findByParentStepId(UUID parentStepId);

    List<ScenarioStep> findByScenarioIdAndParentStepIdIsNullOrderByOrderIndexAsc(UUID scenarioId);

    List<ScenarioStep> findByScenarioIdAndParentStepId(UUID scenarioId, UUID parentStepId);

    /** Для конфликт-чека при удалении Entry Point / Block: какие шаги (в т.ч. в чужих сценариях) на него ссылаются. */
    List<ScenarioStep> findByCalledEntryPointIdIn(List<UUID> entryPointIds);
}
