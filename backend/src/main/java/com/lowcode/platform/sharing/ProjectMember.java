package com.lowcode.platform.sharing;

import jakarta.persistence.*;

import java.util.UUID;

/**
 * Роль на уровне Project (грубая гранулярность) — см. V9-миграцию и
 * обсуждение гибридной модели прав (Project — грубо, Scenario — точечно,
 * эффективная роль = max из обоих). OWNER выставляется один раз при
 * создании проекта (см. ProjectService.create()), как и у Collaborator.
 */
@Entity
@Table(name = "project_member")
public class ProjectMember {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
}
