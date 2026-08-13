package com.example.dpop.orchestrator.authorisation;

public record SmsVerifyRequest(Long smsSetupId, String tan) {
}
