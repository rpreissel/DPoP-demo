package com.example.dpop.orchestrator.flow;

public record SmsTanRequest(
        Long smsSetupId,
        String tan
) {
}
