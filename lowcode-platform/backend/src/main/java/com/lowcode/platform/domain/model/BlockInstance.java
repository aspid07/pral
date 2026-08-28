package com.lowcode.platform.domain.model;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "block_instance")
public class BlockInstance {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "scheme_id", nullable = false)
    private UUID schemeId;

    @Column(name = "block_type_id", nullable = false)
    private UUID blockTypeId;

    @Column(nullable = false)
    private String label;

    // Позиция на холсте — нужна фронтенду для раскладки; конфликты позиций
    // разрешаются на уровне realtime co-editing канала (Yjs), не здесь.
    private double x;
    private double y;

    public UUID getId() { return id; }
    public UUID getSchemeId() { return schemeId; }
    public void setSchemeId(UUID schemeId) { this.schemeId = schemeId; }
    public UUID getBlockTypeId() { return blockTypeId; }
    public void setBlockTypeId(UUID blockTypeId) { this.blockTypeId = blockTypeId; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public double getX() { return x; }
    public void setX(double x) { this.x = x; }
    public double getY() { return y; }
    public void setY(double y) { this.y = y; }
}
