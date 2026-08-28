package com.lowcode.platform.domain.repository;

import com.lowcode.platform.domain.model.BlockType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BlockTypeRepository extends JpaRepository<BlockType, UUID> {
}
