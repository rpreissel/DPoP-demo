package com.example.dpop.orchestrator.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "channel_session")
public class ChannelSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "channel_session_id", nullable = false)
    private UUID channelSessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private Channel channel;

    @Column(name = "binding_key_ref", nullable = false, length = 64)
    private String bindingKeyRef;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_accessed_at", nullable = false)
    private Instant lastAccessedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected ChannelSession() {
    }

    public ChannelSession(Channel channel, String bindingKeyRef, Instant expiresAt) {
        this.channelSessionId = UUID.randomUUID();
        this.channel = channel;
        this.bindingKeyRef = bindingKeyRef;
        this.createdAt = Instant.now();
        this.lastAccessedAt = Instant.now();
        this.expiresAt = expiresAt;
    }

    public UUID getChannelSessionId() {
        return channelSessionId;
    }

    public Channel getChannel() {
        return channel;
    }

    public String getBindingKeyRef() {
        return bindingKeyRef;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastAccessedAt() {
        return lastAccessedAt;
    }

    public void touch() {
        this.lastAccessedAt = Instant.now();
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Long getVersion() {
        return version;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public enum Channel {
        APP, WEB
    }
}
