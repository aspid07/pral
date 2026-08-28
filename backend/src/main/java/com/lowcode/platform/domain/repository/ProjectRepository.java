package com.lowcode.platform.domain.repository;

import com.lowcode.platform.domain.model.Project;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    // Ревью CTO, п.1.3: раньше findAll() отдавал все проекты в базе вне
    // зависимости от прав — "Список проектов, доступных пользователю"
    // (api-contract.md) не выполнялся.
    @Query("""
            select p from Project p
            where exists (
                select 1 from ProjectMember m
                where m.projectId = p.id and m.userId = :userId
            )
            """)
    Page<Project> findAllVisibleTo(@Param("userId") UUID userId, Pageable pageable);
}
