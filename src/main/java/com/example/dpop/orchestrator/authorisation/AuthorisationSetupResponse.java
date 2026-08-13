package com.example.dpop.orchestrator.authorisation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.example.dpop.orchestrator.session.NextStep;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthorisationSetupResponse(
        UUID authorisationSessionId,
        NextStep next
) {
    public AuthorisationSetupResponse(NextStep next) {
        this(null, next);
    }
}
