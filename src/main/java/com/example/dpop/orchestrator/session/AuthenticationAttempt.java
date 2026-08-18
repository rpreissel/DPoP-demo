package com.example.dpop.orchestrator.session;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

import java.time.Instant;
import java.util.UUID;

@Entity
@DiscriminatorValue("authentication")
public class AuthenticationAttempt extends OrchestratorAttempt {

    protected AuthenticationAttempt() {
    }

    public AuthenticationAttempt(UUID processSessionId, String nextContext, String nextStep, Instant expiresAt) {
        super(processSessionId, nextContext, nextStep, expiresAt);
    }
}
