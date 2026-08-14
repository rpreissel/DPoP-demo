package com.example.dpop.orchestrator.flow;

import com.example.dpop.orchestrator.session.BindingSession;
import com.example.dpop.orchestrator.session.NextStep;

import java.util.Map;

public interface IdentificationMethodHandler {

    String method();

    NextStep start(BindingSession session, Map<String, Object> request);

    NextStep submit(BindingSession session, Map<String, Object> request);
}
