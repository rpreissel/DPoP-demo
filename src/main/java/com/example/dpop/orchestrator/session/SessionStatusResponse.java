package com.example.dpop.orchestrator.session;

import java.util.List;
import java.util.UUID;

public record SessionStatusResponse(
        UUID registrationSessionId,
        UUID authorisationSessionId,
        String nextStep,
        List<String> identificationMeans
) {
}
