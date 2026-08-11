package com.example.dpop.orchestrator.registration;

import com.example.dpop.orchestrator.session.NextStep;

import java.util.UUID;

public record RegistrationSetupResponse(
        UUID registrationSessionId,
        NextStep next
) {
}
