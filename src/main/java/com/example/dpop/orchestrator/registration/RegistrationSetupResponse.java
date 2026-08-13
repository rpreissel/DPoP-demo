package com.example.dpop.orchestrator.registration;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.example.dpop.orchestrator.session.NextStep;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegistrationSetupResponse(
        UUID registrationSessionId,
        UUID authorisationSessionId,
        UUID sessionId,
        NextStep next
) {
    public RegistrationSetupResponse(NextStep next) {
        this(null, null, null, next);
    }

    public RegistrationSetupResponse(UUID registrationSessionId, NextStep next) {
        this(registrationSessionId, null, null, next);
    }

    public RegistrationSetupResponse(UUID registrationSessionId, UUID authorisationSessionId, NextStep next) {
        this(registrationSessionId, authorisationSessionId, null, next);
    }
}
