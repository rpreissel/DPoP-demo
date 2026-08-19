package com.example.dpop.orchestrator.flow;

import com.example.dpop.orchestrator.session.BindingSession;
import com.example.dpop.orchestrator.session.NextStep;

import java.util.Map;

@FunctionalInterface
public interface CommandExecutor {
    NextStep execute(BindingSession session, Map<String, Object> request);
}
