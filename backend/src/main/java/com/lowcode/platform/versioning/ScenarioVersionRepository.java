package com.lowcode.platform.versioning;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScenarioVersionRepository extends JpaRepository<ScenarioVersion, UUID> {
    List<ScenarioVersion> findByScenarioIdOrderByVersionNumberAsc(UUID scenarioId);

    Optional<ScenarioVersion> findByIdAndScenarioId(UUID id, UUID scenarioId);

    long countByScenarioId(UUID scenarioId);
}
