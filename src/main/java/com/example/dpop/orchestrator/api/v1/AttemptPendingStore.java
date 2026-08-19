package com.example.dpop.orchestrator.api.v1;

import com.example.dpop.orchestrator.session.OrchestratorAttempt;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

@Component
public class AttemptPendingStore {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public AttemptPendingStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public <T> T load(OrchestratorAttempt attempt, Class<T> type) {
        String result = attempt.getResult();
        if (result == null || result.isBlank()) return null;
        try {
            Map<String, Object> parsed = objectMapper.readValue(result, MAP_TYPE);
            Object pending = parsed.get("pending");
            if (pending == null) return null;
            return objectMapper.convertValue(pending, type);
        } catch (Exception e) {
            return null;
        }
    }

    public void save(OrchestratorAttempt attempt, Object pending) {
        try {
            attempt.setResult(objectMapper.writeValueAsString(Map.of("pending", pending)));
        } catch (Exception e) {
            throw new IllegalStateException("Pending-Daten konnten nicht serialisiert werden", e);
        }
    }
}
