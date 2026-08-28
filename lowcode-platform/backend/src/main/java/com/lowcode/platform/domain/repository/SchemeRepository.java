package com.lowcode.platform.domain.repository;

import com.lowcode.platform.domain.model.Scheme;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SchemeRepository extends JpaRepository<Scheme, UUID> {
    Optional<Scheme> findByProjectId(UUID projectId);
}
