package com.example.dpop.auth_sms;

/**
 * Result of a new SMS enrollment. The phone number is intentionally not included —
 * it stays within the auth_sms module.
 */
public record AuthSmsEnrollResult(
        Long enrollmentId,
        String tan
) {
}
