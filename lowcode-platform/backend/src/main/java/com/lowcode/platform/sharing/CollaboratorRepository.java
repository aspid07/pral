package com.lowcode.platform.sharing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CollaboratorRepository extends JpaRepository<Collaborator, UUID> {
    List<Collaborator> findByScenarioId(UUID scenarioId);

    Optional<Collaborator> findByScenarioIdAndUserId(UUID scenarioId, UUID userId);
}
