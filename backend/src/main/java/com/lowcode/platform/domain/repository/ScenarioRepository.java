package com.lowcode.platform.domain.repository;

import com.lowcode.platform.domain.model.Scenario;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScenarioRepository extends JpaRepository<Scenario, UUID> {
    Optional<Scenario> findByEntryPointId(UUID entryPointId);

    List<Scenario> findByEntryPointIdIn(List<UUID> entryPointIds);

    // Ревью CTO, п.1.3 — та же логика видимости, что и PermissionService
    // (Collaborator ИЛИ ProjectMember на домашнем проекте), только как SQL
    // для фильтрации списка, а не поштучный расчёт роли. Нативный запрос,
    // не JPQL: цепочка entry_point -> block_instance -> scheme -> project
    // проще одним SQL, чем через join нескольких JPA-сущностей из разных пакетов.
    @Query(value = """
            select s.* from scenario s
            where exists (select 1 from collaborator c where c.scenario_id = s.id and c.user_id = :userId)
               or exists (
                    select 1 from entry_point ep
                    join block_instance bi on bi.id = ep.block_instance_id
                    join scheme sch on sch.id = bi.scheme_id
                    join project_member pm on pm.project_id = sch.project_id and pm.user_id = :userId
                    where ep.id = s.entry_point_id
               )
            """,
            countQuery = """
            select count(*) from scenario s
            where exists (select 1 from collaborator c where c.scenario_id = s.id and c.user_id = :userId)
               or exists (
                    select 1 from entry_point ep
                    join block_instance bi on bi.id = ep.block_instance_id
                    join scheme sch on sch.id = bi.scheme_id
                    join project_member pm on pm.project_id = sch.project_id and pm.user_id = :userId
                    where ep.id = s.entry_point_id
               )
            """,
            nativeQuery = true)
    Page<Scenario> findAllVisibleTo(@Param("userId") UUID userId, Pageable pageable);
}
