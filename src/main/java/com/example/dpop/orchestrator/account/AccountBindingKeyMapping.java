package com.example.dpop.orchestrator.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "account_binding_key_mapping")
public class AccountBindingKeyMapping {

    @Id
    @Column(name = "binding_key_ref", nullable = false, length = 64)
    private String bindingKeyRef;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AccountBindingKeyMapping() {
    }

    public AccountBindingKeyMapping(String bindingKeyRef, Long accountId, Instant createdAt) {
        this.bindingKeyRef = bindingKeyRef;
        this.accountId = accountId;
        this.createdAt = createdAt;
    }

    public String getBindingKeyRef() {
        return bindingKeyRef;
    }

    public Long getAccountId() {
        return accountId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
