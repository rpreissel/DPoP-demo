package com.example.dpop.auth_sms;

public record AuthSmsSetupResult(
        Long smsSetupId,
        String phoneNumber,
        String tan,
        boolean testSmsSent
) {
}
