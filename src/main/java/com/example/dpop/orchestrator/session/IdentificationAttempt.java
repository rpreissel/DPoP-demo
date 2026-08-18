package com.example.dpop.orchestrator.session;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

import java.time.Instant;
import java.util.UUID;

@Entity
@DiscriminatorValue("IDENTIFICATION")
public class IdentificationAttempt extends OrchestratorAttempt {

    protected IdentificationAttempt() {
    }

    public IdentificationAttempt(UUID processSessionId, String nextContext, String nextStep, Instant expiresAt) {
        super(processSessionId, nextContext, nextStep, expiresAt);
    }
}
