package com.example.dpop.orchestrator.registration;

public record FscIdentificationRequest(
        String kvnr,
        String name,
        String vorname
) {
}
