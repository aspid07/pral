package com.lowcode.platform.domain.repository;

import com.lowcode.platform.domain.model.EntryPoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EntryPointRepository extends JpaRepository<EntryPoint, UUID> {
    List<EntryPoint> findByBlockInstanceId(UUID blockInstanceId);

    List<EntryPoint> findByBlockInstanceIdIn(List<UUID> blockInstanceIds);
}
