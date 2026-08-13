package com.example.dpop.orchestrator.flow;

import com.example.dpop.orchestrator.session.NextStep;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FlowSetupResponse(
        UUID sessionId,
        NextStep next
) {
}
