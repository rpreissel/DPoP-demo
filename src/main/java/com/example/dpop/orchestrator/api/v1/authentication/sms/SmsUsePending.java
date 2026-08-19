package com.example.dpop.orchestrator.api.v1.authentication.sms;

import com.example.dpop.auth_sms.EnrollmentRef;

import java.util.List;
import java.util.Map;

public record SmsUsePending(
        EnrollmentRef enrollmentRef,
        String tan) {

    public SmsUsePending merge(Map<String, Object> patch) {
        if (patch == null) return this;
        String newTan = patch.containsKey("tan") ? (String) patch.get("tan") : tan;
        return new SmsUsePending(enrollmentRef, newTan);
    }

    public List<String> missingUserInputs() {
        if (tan == null || tan.isBlank()) return List.of("tan");
        return List.of();
    }
}
