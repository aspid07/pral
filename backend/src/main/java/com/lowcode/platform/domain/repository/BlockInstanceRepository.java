package com.lowcode.platform.domain.repository;

import com.lowcode.platform.domain.model.BlockInstance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BlockInstanceRepository extends JpaRepository<BlockInstance, UUID> {
    List<BlockInstance> findBySchemeId(UUID schemeId);

    Optional<BlockInstance> findByIdAndSchemeId(UUID id, UUID schemeId);
}
