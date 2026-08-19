package com.example.dpop.orchestrator.flow.handler;

import com.example.dpop.orchestrator.flow.AuthenticationMethodHandler;
import com.example.dpop.orchestrator.flow.FlowSessionException;
import com.example.dpop.orchestrator.session.BindingSession;
import com.example.dpop.orchestrator.session.NextStep;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class PasswordAuthenticationHandler implements AuthenticationMethodHandler {

    @Override
    public String method() {
        return "password";
    }

    public NextStep start(BindingSession session, Map<String, Object> request) {
        throw new FlowSessionException("Password authentication is not wired to a backend yet");
    }

    public NextStep verify(BindingSession session, Map<String, Object> request) {
        throw new FlowSessionException("Password authentication is not wired to a backend yet");
    }
}
