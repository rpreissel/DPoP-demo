package com.example.dpop.orchestrator.api.v1;

import org.springframework.http.HttpStatus;

public class OrchestratorException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public OrchestratorException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public static OrchestratorException notFound(String message) {
        return new OrchestratorException(HttpStatus.GONE, "ATTEMPT_NOT_FOUND", message);
    }

    public static OrchestratorException conflict(String message) {
        return new OrchestratorException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", message);
    }

    public static OrchestratorException verificationFailed(String message) {
        return new OrchestratorException(HttpStatus.UNPROCESSABLE_ENTITY, "VERIFICATION_FAILED", message);
    }

    public static OrchestratorException forbidden(String message) {
        return new OrchestratorException(HttpStatus.FORBIDDEN, "BINDING_MISMATCH", message);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
