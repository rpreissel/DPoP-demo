package com.example.dpop.kcext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure logic, no Keycloak runtime needed - {@link OrchestratorClient.Next} classifies the
 * orchestrator's own {@code next} shape (docs/05-api.md #2) into what
 * {@code OrchestratorAuthenticator.handleResponse} branches on. Written after a real bug: REGISTER's
 * {@code Identifying} state (docs/04-orchestrierung.md #4/#5) reports
 * {@code step="selectIdentificationMethod"}, not the {@code "selectMethod"} every other
 * OfferingState's default uses - {@link OrchestratorClient.Next#isSelectMethod()} only recognized
 * the latter, so a fresh Web registration hit Keycloak's generic "Unhandled orchestrator next"
 * failure page the first time this path was ever actually driven end-to-end (no test caught it
 * beforehand, since this module had no test source set at all until now).
 */
class NextClassificationTest {

    @Test
    void toolStepIsClassifiedAsTool() {
        var next = new OrchestratorClient.Next("tool", "enroll-sms", null, "enroll", "session-1");
        assertTrue(next.isTool());
        assertFalse(next.isSelectMethod());
        assertFalse(next.isAuthenticated());
    }

    @Test
    void orchestratorSelectMethodStepIsClassifiedAsSelectMethod() {
        var next = new OrchestratorClient.Next("orchestrator", null, "auth", "selectMethod", null);
        assertTrue(next.isSelectMethod());
    }

    @Test
    void identifyingsOwnSelectIdentificationMethodStepIsAlsoClassifiedAsSelectMethod() {
        // The regression case: FastAccessState.Identifying.selectionStep is deliberately named
        // differently from every other OfferingState's default "selectMethod" step, but it is the
        // exact same kind of screen (a list of candidates to choose from) - see this class's own
        // KDoc.
        var next = new OrchestratorClient.Next("orchestrator", null, "registration", "selectIdentificationMethod", null);
        assertTrue(next.isSelectMethod());
    }

    @Test
    void authenticatedStepIsClassifiedAsAuthenticated() {
        var next = new OrchestratorClient.Next("orchestrator", null, "authentication", "authenticated", null);
        assertTrue(next.isAuthenticated());
        assertFalse(next.isSelectMethod());
    }

    @Test
    void fromJsonParsesEveryField() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.readTree(
                "{\"type\":\"tool\",\"toolId\":\"ident-fsc\",\"step\":\"input\",\"toolSessionId\":\"abc-123\"}"
        );
        var next = OrchestratorClient.Next.from(json);
        assertTrue(next.isTool());
        assertTrue(next.toolId().equals("ident-fsc"));
        assertTrue(next.toolSessionId().equals("abc-123"));
    }
}
