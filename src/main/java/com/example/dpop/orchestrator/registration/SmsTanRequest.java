package com.example.dpop.orchestrator.registration;

public record SmsTanRequest(
        Long smsSetupId,
        String tan
) {
}
