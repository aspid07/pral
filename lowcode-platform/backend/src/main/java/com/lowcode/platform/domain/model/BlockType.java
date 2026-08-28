package com.lowcode.platform.domain.model;

import jakarta.persistence.*;
import java.util.UUID;

/**
 * Справочник типов блоков. MVP: ACTOR, MICROSERVICE, DATABASE, MESSAGE_BROKER, CACHE.
 * Список открыт для расширения — цель: полное покрытие типов интеграций архитектора.
 */
@Entity
@Table(name = "block_type")
public class BlockType {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private String code; // ACTOR | MICROSERVICE | DATABASE | MESSAGE_BROKER | CACHE

    @Column(nullable = false)
    private String displayName;

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
}
