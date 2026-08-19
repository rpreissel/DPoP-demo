package com.example.dpop.orchestrator.api.v1.authentication.sms;

import com.example.dpop.auth_sms.EnrollmentRef;

import java.util.List;

public sealed interface UseStep {
    record NeedInput(List<String> missingFields) implements UseStep {}
    record VerifyChallenge(EnrollmentRef ref, String tan) implements UseStep {}
}
