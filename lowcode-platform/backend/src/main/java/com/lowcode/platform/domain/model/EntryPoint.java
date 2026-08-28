package com.lowcode.platform.domain.model;

import jakarta.persistence.*;
import java.util.UUID;

/**
 * Метод / событие / WS-канал на конкретном блоке. Глобально адресуемая
 * сущность — на неё может сослаться Сценарий из любого проекта.
 */
@Entity
@Table(name = "entry_point")
public class EntryPoint {

    public enum Kind { SYNC_METHOD, ASYNC_EVENT, WEBSOCKET_CHANNEL }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "block_instance_id", nullable = false)
    private UUID blockInstanceId;

    @Column(nullable = false)
    private String name; // напр. "POST /orders", "OrderCreated"

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    public UUID getId() { return id; }
    public UUID getBlockInstanceId() { return blockInstanceId; }
    public void setBlockInstanceId(UUID blockInstanceId) { this.blockInstanceId = blockInstanceId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Kind getKind() { return kind; }
    public void setKind(Kind kind) { this.kind = kind; }
}
