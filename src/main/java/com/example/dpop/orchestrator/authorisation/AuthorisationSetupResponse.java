package com.example.dpop.orchestrator.authorisation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.example.dpop.orchestrator.session.NextStep;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthorisationSetupResponse(
        UUID authorisationSessionId,
        UUID sessionId,
        NextStep next
) {
    public AuthorisationSetupResponse(NextStep next) {
        this(null, null, next);
    }

    public AuthorisationSetupResponse(UUID authorisationSessionId, NextStep next) {
        this(authorisationSessionId, authorisationSessionId, next);
    }
}
