package com.example.dpop.orchestrator.flow;

import com.example.dpop.orchestrator.session.BindingSession;
import com.example.dpop.orchestrator.session.NextStep;

import java.util.Map;

public interface AuthenticationMethodHandler {

    String method();

    NextStep start(BindingSession session, Map<String, Object> request);

    NextStep verify(BindingSession session, Map<String, Object> request);
}
