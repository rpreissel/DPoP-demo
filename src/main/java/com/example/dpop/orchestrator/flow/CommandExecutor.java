package com.example.dpop.orchestrator.flow;

import com.example.dpop.orchestrator.session.BindingSession;
import com.example.dpop.orchestrator.session.NextStep;

@FunctionalInterface
public interface CommandExecutor<T> {
    NextStep execute(BindingSession session, T request);
}
