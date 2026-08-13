package com.example.dpop.orchestrator.session;

import java.util.UUID;

public record SessionStatusResponse(
        UUID registrationSessionId,
        UUID authorisationSessionId,
        UUID sessionId,
        NextStep next
) {
}
