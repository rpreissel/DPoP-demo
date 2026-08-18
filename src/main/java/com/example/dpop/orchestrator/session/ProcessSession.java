package com.example.dpop.orchestrator.session;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "process_session")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "purpose", discriminatorType = DiscriminatorType.STRING)
public abstract class ProcessSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "process_session_id", nullable = false)
    private UUID processSessionId;

    @Column(name = "channel_session_id", nullable = false)
    private UUID channelSessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 20, insertable = false, updatable = false)
    private ProcessPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProcessStatus status;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "selected_identification_method", length = 50)
    private String selectedIdentificationMethod;

    @Column(name = "selected_authentication_method", length = 50)
    private String selectedAuthenticationMethod;

    @Column(name = "person_id")
    private Long personId;

    @Lob
    @Column(name = "pending_challenge")
    private String pendingChallenge;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected ProcessSession() {
    }

    protected ProcessSession(UUID channelSessionId, ProcessPurpose purpose, Instant expiresAt) {
        this.processSessionId = UUID.randomUUID();
        this.channelSessionId = channelSessionId;
        this.purpose = purpose;
        this.status = ProcessStatus.ACTIVE;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
    }

    public UUID getProcessSessionId() {
        return processSessionId;
    }

    public UUID getChannelSessionId() {
        return channelSessionId;
    }

    public ProcessPurpose getPurpose() {
        return purpose;
    }

    public ProcessStatus getStatus() {
        return status;
    }

    public void setStatus(ProcessStatus status) {
        this.status = status;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getSelectedIdentificationMethod() {
        return selectedIdentificationMethod;
    }

    public void setSelectedIdentificationMethod(String selectedIdentificationMethod) {
        this.selectedIdentificationMethod = selectedIdentificationMethod;
    }

    public String getSelectedAuthenticationMethod() {
        return selectedAuthenticationMethod;
    }

    public void setSelectedAuthenticationMethod(String selectedAuthenticationMethod) {
        this.selectedAuthenticationMethod = selectedAuthenticationMethod;
    }

    public Long getPersonId() {
        return personId;
    }

    public void setPersonId(Long personId) {
        this.personId = personId;
    }

    public String getPendingChallenge() {
        return pendingChallenge;
    }

    public void setPendingChallenge(String pendingChallenge) {
        this.pendingChallenge = pendingChallenge;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public void consume() {
        this.consumedAt = Instant.now();
        this.status = ProcessStatus.COMPLETED;
    }

    public Long getVersion() {
        return version;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }
}
