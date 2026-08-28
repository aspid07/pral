package com.lowcode.platform.domain.model;

import jakarta.persistence.*;
import java.util.UUID;

/**
 * Общая схема проекта — все блоки и связи без конкретики сценария.
 */
@Entity
@Table(name = "scheme")
public class Scheme {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "project_id", nullable = false, unique = true)
    private UUID projectId;

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
}
