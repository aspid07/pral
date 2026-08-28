package com.lowcode.platform.domain.model;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "connection")
public class Connection {

    public enum IntegrationType { API, ASYNC, WEBSOCKET }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "scheme_id", nullable = false)
    private UUID schemeId;

    @Column(name = "source_block_id", nullable = false)
    private UUID sourceBlockId;

    @Column(name = "target_block_id", nullable = false)
    private UUID targetBlockId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IntegrationType integrationType;

    // true — связь появилась автоматически из межпроектного вызова Сценария
    // (см. Вариант Б из системного анализа), а не нарисована руками
    private boolean isExternal;

    public UUID getId() { return id; }
    public UUID getSchemeId() { return schemeId; }
    public void setSchemeId(UUID schemeId) { this.schemeId = schemeId; }
    public UUID getSourceBlockId() { return sourceBlockId; }
    public void setSourceBlockId(UUID sourceBlockId) { this.sourceBlockId = sourceBlockId; }
    public UUID getTargetBlockId() { return targetBlockId; }
    public void setTargetBlockId(UUID targetBlockId) { this.targetBlockId = targetBlockId; }
    public IntegrationType getIntegrationType() { return integrationType; }
    public void setIntegrationType(IntegrationType integrationType) { this.integrationType = integrationType; }
    public boolean isExternal() { return isExternal; }
    public void setExternal(boolean external) { isExternal = external; }
}
