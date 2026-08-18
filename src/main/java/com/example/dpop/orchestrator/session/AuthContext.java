package com.example.dpop.orchestrator.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "auth_context")
public class AuthContext {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "auth_context_id", nullable = false)
    private UUID authContextId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "keycloak_session_id", length = 255)
    private String keycloakSessionId;

    @Column(name = "keycloak_subject", length = 255)
    private String keycloakSubject;

    @Column(name = "token_handle", length = 255)
    private String tokenHandle;

    @Column(name = "current_acr", length = 50)
    private String currentAcr;

    @Lob
    @Column(name = "current_amr")
    private String currentAmr;

    @Column(name = "auth_time", nullable = false)
    private Instant authTime;

    @Column(name = "token_expires_at")
    private Instant tokenExpiresAt;

    @Column(name = "refresh_expires_at")
    private Instant refreshExpiresAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected AuthContext() {
    }

    public AuthContext(Long accountId, String keycloakSessionId, String keycloakSubject) {
        this.authContextId = UUID.randomUUID();
        this.accountId = accountId;
        this.keycloakSessionId = keycloakSessionId;
        this.keycloakSubject = keycloakSubject;
        this.authTime = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getAuthContextId() {
        return authContextId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getKeycloakSessionId() {
        return keycloakSessionId;
    }

    public void setKeycloakSessionId(String keycloakSessionId) {
        this.keycloakSessionId = keycloakSessionId;
    }

    public String getKeycloakSubject() {
        return keycloakSubject;
    }

    public void setKeycloakSubject(String keycloakSubject) {
        this.keycloakSubject = keycloakSubject;
    }

    public String getTokenHandle() {
        return tokenHandle;
    }

    public void setTokenHandle(String tokenHandle) {
        this.tokenHandle = tokenHandle;
    }

    public String getCurrentAcr() {
        return currentAcr;
    }

    public void setCurrentAcr(String currentAcr) {
        this.currentAcr = currentAcr;
    }

    public String getCurrentAmr() {
        return currentAmr;
    }

    public void setCurrentAmr(String currentAmr) {
        this.currentAmr = currentAmr;
    }

    public Instant getAuthTime() {
        return authTime;
    }

    public Instant getTokenExpiresAt() {
        return tokenExpiresAt;
    }

    public void setTokenExpiresAt(Instant tokenExpiresAt) {
        this.tokenExpiresAt = tokenExpiresAt;
    }

    public Instant getRefreshExpiresAt() {
        return refreshExpiresAt;
    }

    public void setRefreshExpiresAt(Instant refreshExpiresAt) {
        this.refreshExpiresAt = refreshExpiresAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
