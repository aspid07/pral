package com.lowcode.platform.domain.repository;

import com.lowcode.platform.domain.model.Connection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ConnectionRepository extends JpaRepository<Connection, UUID> {
    List<Connection> findBySchemeId(UUID schemeId);

    List<Connection> findBySourceBlockIdOrTargetBlockId(UUID sourceBlockId, UUID targetBlockId);
}
