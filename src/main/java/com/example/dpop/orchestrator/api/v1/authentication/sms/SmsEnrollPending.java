package com.example.dpop.orchestrator.api.v1.authentication.sms;

import com.example.dpop.auth_sms.EnrollmentRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record SmsEnrollPending(
        String phoneNumber,
        EnrollmentRef enrollmentRef,
        String tan,
        boolean tanVerified,
        boolean enrollmentConfirmed) {

    public static SmsEnrollPending empty() {
        return new SmsEnrollPending(null, null, null, false, false);
    }

    public SmsEnrollPending merge(Map<String, Object> patch) {
        if (patch == null) return this;
        String phone = patch.containsKey("phoneNumber") ? (String) patch.get("phoneNumber") : phoneNumber;
        String newTan = patch.containsKey("tan") ? (String) patch.get("tan") : tan;
        return new SmsEnrollPending(phone, enrollmentRef, newTan, tanVerified, enrollmentConfirmed);
    }

    public SmsEnrollPending withEnrollmentRef(EnrollmentRef ref) {
        return new SmsEnrollPending(phoneNumber, ref, tan, false, false);
    }

    public SmsEnrollPending withTanVerified() {
        return new SmsEnrollPending(phoneNumber, enrollmentRef, tan, true, false);
    }

    public SmsEnrollPending withEnrollmentConfirmed() {
        return new SmsEnrollPending(phoneNumber, enrollmentRef, tan, true, true);
    }

    public List<String> missingUserInputs() {
        List<String> missing = new ArrayList<>();
        if (phoneNumber == null || phoneNumber.isBlank()) missing.add("phoneNumber");
        if (tan == null || tan.isBlank()) missing.add("tan");
        return missing;
    }
}
