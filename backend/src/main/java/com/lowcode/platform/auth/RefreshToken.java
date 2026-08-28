package com.lowcode.platform.auth;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Опредмеченная строка активной/отозванной refresh-сессии. Сам секрет
 * пользователю никогда не возвращается из этой сущности назад — только при
 * выдаче (см. RefreshTokenService.issue/rotate), здесь лежит лишь его хеш.
 * Raw UUID userId, не @ManyToOne на AppUser — тот же стиль, что и
 * Scenario.ownerId/Collaborator.userId в остальном домене (см. Scenario.java).
 */
@Entity
@Table(name = "refresh_token")
public class RefreshToken {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by")
    private UUID replacedBy;

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public Instant getIssuedAt() { return issuedAt; }
    public void setIssuedAt(Instant issuedAt) { this.issuedAt = issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
    public UUID getReplacedBy() { return replacedBy; }
    public void setReplacedBy(UUID replacedBy) { this.replacedBy = replacedBy; }
}
