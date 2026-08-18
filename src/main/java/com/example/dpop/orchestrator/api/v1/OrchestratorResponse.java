package com.example.dpop.orchestrator.api.v1;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrchestratorResponse(
        UUID channelSessionId,
        ProcessState processState,
        AttemptState attemptState,
        NextRouting next,
        DemoHints _demo
) {
    public OrchestratorResponse(UUID channelSessionId, NextRouting next) {
        this(channelSessionId, null, null, next, null);
    }

    public OrchestratorResponse(UUID channelSessionId, ProcessState processState, NextRouting next) {
        this(channelSessionId, processState, null, next, null);
    }

    public OrchestratorResponse(UUID channelSessionId, ProcessState processState, AttemptState attemptState, NextRouting next) {
        this(channelSessionId, processState, attemptState, next, null);
    }

    /** Only present in demo/test mode — never included in production responses. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DemoHints(
            String tan,
            String note
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProcessState(
            String purpose,
            String status,
            Long personId,
            Long accountId
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AttemptState(
            UUID attemptId,
            String attemptType,
            String status,
            List<String> missingFields,
            Object result
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NextRouting(
            String context,
            String step,
            List<String> methods,
            String enrollmentRef,
            Long accountId,
            Long personId
    ) {
        public NextRouting(String context, String step) {
            this(context, step, null, null, null, null);
        }

        public NextRouting(String context, String step, List<String> methods) {
            this(context, step, methods, null, null, null);
        }

        public NextRouting(String context, String step, List<String> methods, String enrollmentRef) {
            this(context, step, methods, enrollmentRef, null, null);
        }
    }
}
