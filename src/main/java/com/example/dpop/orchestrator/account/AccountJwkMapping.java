package com.example.dpop.orchestrator.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "account_jwk_mapping")
public class AccountJwkMapping {

    @Id
    @Column(name = "jwk_thumbprint", nullable = false, length = 64)
    private String jwkThumbprint;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AccountJwkMapping() {
    }

    public AccountJwkMapping(String jwkThumbprint, Long accountId, Instant createdAt) {
        this.jwkThumbprint = jwkThumbprint;
        this.accountId = accountId;
        this.createdAt = createdAt;
    }

    public String getJwkThumbprint() {
        return jwkThumbprint;
    }

    public Long getAccountId() {
        return accountId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
