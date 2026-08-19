package com.example.dpop.orchestrator.api.v1.authentication.sms;

import com.example.dpop.auth_sms.EnrollmentRef;

import java.util.List;

public sealed interface EnrollStep {
    record NeedInput(List<String> missingFields)               implements EnrollStep {}
    record StartEnrollment(String phoneNumber)                  implements EnrollStep {}
    record ConfirmEnrollment(EnrollmentRef ref, String tan)    implements EnrollStep {}
    record ActivateMethod(EnrollmentRef ref)                    implements EnrollStep {}
}
