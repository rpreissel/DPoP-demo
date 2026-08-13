package com.example.dpop.orchestrator.flow;

import com.example.dpop.orchestrator.session.ClientSession;
import com.example.dpop.orchestrator.session.NextStep;

import java.util.Map;

public interface AuthenticationMethodHandler {

    String method();

    NextStep start(ClientSession session, Map<String, Object> request);

    NextStep verify(ClientSession session, Map<String, Object> request);
}
