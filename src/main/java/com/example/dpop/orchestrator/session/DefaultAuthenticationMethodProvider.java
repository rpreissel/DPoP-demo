package com.example.dpop.orchestrator.session;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DefaultAuthenticationMethodProvider implements AuthenticationMethodProvider {

    @Override
    public List<String> availableMethods() {
        return List.of("sms");
    }
}
