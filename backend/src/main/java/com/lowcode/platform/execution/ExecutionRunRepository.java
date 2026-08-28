package com.lowcode.platform.execution;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ExecutionRunRepository extends JpaRepository<ExecutionRun, UUID> {
    long countByScenarioId(UUID scenarioId);
}
