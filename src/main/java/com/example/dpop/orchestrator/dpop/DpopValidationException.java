package com.example.dpop.orchestrator.dpop;

public class DpopValidationException extends RuntimeException {

    public DpopValidationException(String message) {
        super(message);
    }

    public DpopValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
