package com.example.dpop.auth_sms;

public record AuthSmsTanRequest(
        Long smsSetupId,
        String tan
) {
}
