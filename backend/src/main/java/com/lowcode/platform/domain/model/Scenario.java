package com.lowcode.platform.domain.model;

import jakarta.persistence.*;
import java.util.UUID;

/**
 * Самостоятельная, переиспользуемая (по ссылке, не копии) сущность верхнего
 * уровня — не привязана жёстко к одному Проекту. Реализация ровно одного
 * Entry Point.
 */
@Entity
@Table(name = "scenario")
public class Scenario {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "entry_point_id", nullable = false, unique = true)
    private UUID entryPointId;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    public UUID getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public UUID getEntryPointId() { return entryPointId; }
    public void setEntryPointId(UUID entryPointId) { this.entryPointId = entryPointId; }
    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }
}
