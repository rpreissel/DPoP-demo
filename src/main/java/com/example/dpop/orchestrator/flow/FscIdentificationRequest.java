package com.example.dpop.orchestrator.flow;

public record FscIdentificationRequest(
        String kvnr,
        String name,
        String vorname
) {
}
