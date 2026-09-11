package com.example.dpop.kcext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure logic, no Keycloak runtime needed - the common next-dispatch helper only classifies the
 * orchestrator response into the small sealed outcome hierarchy the callers branch on.
 */
class OrchestratorNextDispatchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void selectMethodWithOptionsYieldsSelect() {
        OrchestratorNextDispatch.Select select = assertInstanceOf(
                OrchestratorNextDispatch.Select.class,
                OrchestratorNextDispatch.classify(next("orchestrator", null, "selectMethod", null), response("one", "two"))
        );

        assertEquals(List.of("one", "two"), select.options());
    }

    @Test
    void selectMethodWithNoOptionsYieldsEmptySelect() {
        OrchestratorNextDispatch.Select select = assertInstanceOf(
                OrchestratorNextDispatch.Select.class,
                OrchestratorNextDispatch.classify(next("orchestrator", null, "selectMethod", null), response())
        );

        assertTrue(select.options().isEmpty());
    }

    @Test
    void toolWithoutSessionIdYieldsAutoActivateTool() {
        OrchestratorNextDispatch.Tool tool = assertInstanceOf(
                OrchestratorNextDispatch.Tool.class,
                OrchestratorNextDispatch.classify(next("tool", "ident-fsc", "input", null), response())
        );

        assertTrue(tool.autoActivate());
        assertEquals("ident-fsc", tool.next().toolId());
    }

    @Test
    void toolWithSessionIdYieldsRenderTool() {
        OrchestratorNextDispatch.Tool tool = assertInstanceOf(
                OrchestratorNextDispatch.Tool.class,
                OrchestratorNextDispatch.classify(next("tool", "ident-fsc", "input", "tool-session-1"), response())
        );

        assertFalse(tool.autoActivate());
        assertEquals("tool-session-1", tool.next().toolSessionId());
    }

    @Test
    void neitherSelectNorToolYieldsUnhandled() {
        OrchestratorNextDispatch.Unhandled unhandled = assertInstanceOf(
                OrchestratorNextDispatch.Unhandled.class,
                OrchestratorNextDispatch.classify(next("orchestrator", null, "somethingElse", null), response())
        );

        assertEquals("somethingElse", unhandled.next().step());
    }

    private static OrchestratorClient.Next next(String type, String toolId, String step, String toolSessionId) {
        return new OrchestratorClient.Next(type, toolId, null, step, toolSessionId);
    }

    private static OrchestratorClient.ChannelResponse response(String... options) {
        Map<String, JsonNode> stepData = new LinkedHashMap<>();
        if (options.length > 0) {
            ArrayNode array = MAPPER.createArrayNode();
            for (String option : options) {
                array.add(option);
            }
            stepData.put("options", array);
        }
        return new OrchestratorClient.ChannelResponse(
                "channel-session-1",
                "STARTED",
                null,
                stepData,
                Map.of(),
                null,
                null,
                Map.of()
        );
    }
}
