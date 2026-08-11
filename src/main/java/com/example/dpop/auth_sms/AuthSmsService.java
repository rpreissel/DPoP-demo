package com.example.dpop.auth_sms;

import org.springframework.stereotype.Service;

@Service
public class AuthSmsService {

    public String authenticate() {
        return "auth_sms: sms code sent";
    }
}
