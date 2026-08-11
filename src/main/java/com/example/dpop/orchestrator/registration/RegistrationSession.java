package com.example.dpop.orchestrator.registration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "registration_session")
public class RegistrationSession {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "jwk_thumbprint", nullable = false, unique = true, length = 64)
    private String jwkThumbprint;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_accessed_at", nullable = false)
    private Instant lastAccessedAt;

    protected RegistrationSession() {
    }

    public RegistrationSession(String jwkThumbprint) {
        this.jwkThumbprint = jwkThumbprint;
        this.createdAt = Instant.now();
        this.lastAccessedAt = this.createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getJwkThumbprint() {
        return jwkThumbprint;
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
}
