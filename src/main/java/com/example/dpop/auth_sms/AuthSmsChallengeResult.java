package com.example.dpop.auth_sms;

/**
 * Result of sending a new TAN challenge for an existing enrollment.
 */
public record AuthSmsChallengeResult(
        Long enrollmentId,
        String tan
) {
}
