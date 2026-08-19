package com.example.dpop.orchestrator.api.v1;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class OrchestratorExceptionHandler {

    @ExceptionHandler(OrchestratorException.class)
    public ResponseEntity<Map<String, Object>> handleOrchestratorException(OrchestratorException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(Map.of("error", ex.getErrorCode(), "message", ex.getMessage()));
    }
}
