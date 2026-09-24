package com.example.dpop.kcext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Resolving the orchestrator's text references against a bundle (docs/adr/ADR-033). */
class OrchestratorTextsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode ref(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    void fillsPlainAndNestedPlaceholders() throws Exception {
        Map<String, String> texts = Map.of(
                "a", "Limit reached: {reason} ({tries})",
                "b", "invalid TAN",
                "f", "Factors: {list}",
                "k", "knowledge",
                "p", "possession");
        assertEquals("Limit reached: invalid TAN (3)",
                OrchestratorTexts.resolve(texts, ref("{\"key\":\"a\",\"args\":{\"tries\":\"3\"},\"texts\":{\"reason\":[{\"key\":\"b\"}]}}")));
        assertEquals("Factors: knowledge, possession",
                OrchestratorTexts.resolve(texts, ref("{\"key\":\"f\",\"texts\":{\"list\":[{\"key\":\"k\"},{\"key\":\"p\"}]}}")));
    }

    @Test
    void anUnknownIdShowsAsItselfAndAValueIsNeverReadAsAPattern() throws Exception {
        assertEquals("3f9a1c0b2e7d", OrchestratorTexts.resolve(Map.of(), ref("{\"key\":\"3f9a1c0b2e7d\"}")));
        assertEquals("costs $1 \\o/", OrchestratorTexts.resolve(Map.of("x", "costs {v}"), ref("{\"key\":\"x\",\"args\":{\"v\":\"$1 \\\\o/\"}}")));
    }
}
