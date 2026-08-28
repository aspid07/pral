package com.lowcode.platform.versioning;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Полный снэпшот сценария на момент сохранения версии.
 * Решение "снэпшоты vs дельты" — открытый вопрос (см. api-contract.md).
 * Снэпшоты выбраны как стартовая реализация: проще инвалидация и чтение
 * произвольной версии, дороже по месту на диске — приемлемо для MVP.
 */
@Entity
@Table(name = "scenario_version")
public class ScenarioVersion {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "scenario_id", nullable = false)
    private UUID scenarioId;

    @Column(nullable = false)
    private int versionNumber;

    // Без @Lob: в Hibernate 6 @Lob на String по умолчанию маппится на Postgres-тип
    // oid (large object), а не text/varchar — расходится с колонкой snapshot_json
    // text в V1__init.sql и валит schema-validation при старте
    // ("wrong column type ... found [text], but expecting [oid]"). Обычный String
    // маппится на varchar/text без проблем, @Lob для этого случая не нужен —
    // это не BLOB/CLOB в смысле JDBC, просто потенциально длинная строка.
    @Column(nullable = false, columnDefinition = "text")
    private String snapshotJson; // сериализованное состояние Scenario + ScenarioStep[]

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getScenarioId() { return scenarioId; }
    public void setScenarioId(UUID scenarioId) { this.scenarioId = scenarioId; }
    public int getVersionNumber() { return versionNumber; }
    public void setVersionNumber(int versionNumber) { this.versionNumber = versionNumber; }
    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String snapshotJson) { this.snapshotJson = snapshotJson; }
    public Instant getCreatedAt() { return createdAt; }
}
