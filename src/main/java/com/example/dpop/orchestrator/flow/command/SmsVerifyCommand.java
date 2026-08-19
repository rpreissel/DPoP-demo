package com.example.dpop.orchestrator.flow.command;

public record SmsVerifyCommand(Long enrollmentId, String tan) {
}
