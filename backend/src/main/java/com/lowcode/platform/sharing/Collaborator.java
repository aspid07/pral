package com.lowcode.platform.sharing;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "collaborator")
public class Collaborator {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "scenario_id", nullable = false)
    private UUID scenarioId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role; // OWNER — создатель; EDITOR — редактирует, не удаляет; READER — только чтение

    public UUID getId() { return id; }
    public UUID getScenarioId() { return scenarioId; }
    public void setScenarioId(UUID scenarioId) { this.scenarioId = scenarioId; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
}
