package com.example.dpop.kcext.webtool;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Everything a tool's own {@link WebToolRenderer#render} needs to draw its current step - the
 * kc-facade's counterpart to the App channel's {@code ToolRenderContext}
 * (frontend/src/tools/types.ts). {@code stepData} and {@code demo} are passed through exactly as
 * the orchestrator sent them (not narrowed to e.g. just {@code missingFields}): different tools
 * need different keys out of both bags, so each renderer picks its own, the same way
 * {@code ToolRenderContext.stepData}/{@code .demo} are untyped bags on the React side too.
 */
public record WebToolRenderContext(
        String toolId,
        String step,
        Map<String, JsonNode> stepData,
        Map<String, JsonNode> demo,
        String error
) {
}
