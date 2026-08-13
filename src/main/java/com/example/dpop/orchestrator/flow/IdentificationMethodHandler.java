package com.example.dpop.orchestrator.flow;

import com.example.dpop.orchestrator.session.ClientSession;
import com.example.dpop.orchestrator.session.NextStep;

import java.util.Map;

public interface IdentificationMethodHandler {

    String method();

    NextStep start(ClientSession session, Map<String, Object> request);

    NextStep submit(ClientSession session, Map<String, Object> request);
}
