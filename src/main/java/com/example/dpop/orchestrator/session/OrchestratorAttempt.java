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
@Table(name = "orchestrator_attempt")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "attempt_type", discriminatorType = DiscriminatorType.STRING)
public abstract class OrchestratorAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "attempt_id", nullable = false)
    private UUID attemptId;

    @Column(name = "process_session_id", nullable = false)
    private UUID processSessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AttemptStatus status;

    @Column(name = "next_context", length = 50)
    private String nextContext;

    @Column(name = "next_step", length = 50)
    private String nextStep;

    @Lob
    @Column(name = "missing_fields")
    private String missingFields;

    @Lob
    @Column(name = "result")
    private String result;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected OrchestratorAttempt() {
    }

    protected OrchestratorAttempt(UUID processSessionId, String nextContext, String nextStep, Instant expiresAt) {
        this.attemptId = UUID.randomUUID();
        this.processSessionId = processSessionId;
        this.status = AttemptStatus.INPUT_REQUIRED;
        this.nextContext = nextContext;
        this.nextStep = nextStep;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
        this.retryCount = 0;
    }

    public UUID getAttemptId() {
        return attemptId;
    }

    public UUID getProcessSessionId() {
        return processSessionId;
    }

    public AttemptStatus getStatus() {
        return status;
    }

    public void setStatus(AttemptStatus status) {
        this.status = status;
    }

    public String getNextContext() {
        return nextContext;
    }

    public void setNextContext(String context) {
        this.nextContext = context;
    }

    public String getNextStep() {
        return nextStep;
    }

    public void setNextStep(String step) {
        this.nextStep = step;
    }

    public String getMissingFields() {
        return missingFields;
    }

    public void setMissingFields(String missingFields) {
        this.missingFields = missingFields;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void incrementRetryCount() {
        this.retryCount++;
    }

    public Long getVersion() {
        return version;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
