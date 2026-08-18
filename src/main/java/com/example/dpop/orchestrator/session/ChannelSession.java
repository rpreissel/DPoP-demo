package com.example.dpop.orchestrator.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 50)
    private ChannelState state;

    @Column(name = "auth_context_id")
    private UUID authContextId;

    @ManyToOne
    @JoinColumn(name = "auth_context_id", insertable = false, updatable = false)
    private AuthContext authContext;

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
        this.state = ChannelState.ANONYMOUS;
        this.createdAt = Instant.now();
        this.lastAccessedAt = Instant.now();
        this.expiresAt = expiresAt;
        this.version = 0L;
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

    public ChannelState getState() {
        return state;
    }

    public void setState(ChannelState state) {
        this.state = state;
    }

    public UUID getAuthContextId() {
        return authContextId;
    }

    public void setAuthContextId(UUID authContextId) {
        this.authContextId = authContextId;
    }

    public AuthContext getAuthContext() {
        return authContext;
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
