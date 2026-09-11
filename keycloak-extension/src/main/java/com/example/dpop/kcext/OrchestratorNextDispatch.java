package com.example.dpop.kcext;

import jakarta.ws.rs.core.MultivaluedMap;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class OrchestratorNextDispatch {

    private OrchestratorNextDispatch() {
    }

    sealed interface Outcome permits Select, Tool, Unhandled {
    }

    record Select(List<String> options) implements Outcome {
    }

    record Tool(OrchestratorClient.Next next, boolean autoActivate) implements Outcome {
    }

    record Unhandled(OrchestratorClient.Next next) implements Outcome {
    }

    /**
     * next must already be non-null and non-authenticated; callers check terminal states first.
     */
    static Outcome classify(OrchestratorClient.Next next, OrchestratorClient.ChannelResponse response) {
        if (next.isSelectMethod()) {
            return new Select(response.stepDataOptions());
        }
        if (next.isTool()) {
            return new Tool(next, next.toolSessionId() == null);
        }
        return new Unhandled(next);
    }

    static OrchestratorClient.ChannelResponse dispatchToolAction(
            OrchestratorClient client, String channelSessionId, String toolId, String toolSessionId,
            MultivaluedMap<String, String> form
    ) throws IOException, InterruptedException {
        if ("true".equals(form.getFirst("orchestrator_abandon"))) {
            return client.abandonTool(channelSessionId, toolSessionId, toolId);
        }
        Map<String, String> fields = new LinkedHashMap<>();
        form.forEach((key, values) -> {
            if (!key.startsWith("orchestrator_") && !values.isEmpty()) fields.put(key, values.get(0));
        });
        return client.patchTool(channelSessionId, toolSessionId, toolId, fields);
    }
}
