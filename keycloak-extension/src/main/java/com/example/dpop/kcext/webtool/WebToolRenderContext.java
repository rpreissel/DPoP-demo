package com.example.dpop.kcext.webtool;

import com.example.dpop.kcext.OrchestratorSettings;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Set;

/**
 * Everything a tool's own {@link WebToolRenderer#render} needs to draw its current step - the
 * kc-facade's counterpart to the App channel's {@code ToolRenderContext}
 * (frontend/src/tools/types.ts). {@code stepData} and {@code demo} are passed through exactly as
 * the orchestrator sent them (not narrowed to e.g. just {@code missingFields}): different tools
 * need different keys out of both bags, so each renderer picks its own, the same way
 * {@code ToolRenderContext.stepData}/{@code .demo} are untyped bags on the React side too.
 *
 * <p>{@code settings} ist die aufgeloeste Realm-Konfiguration statt der {@code KeycloakSession},
 * aus der sie stammt: ein Renderer soll seinen konfigurierten Wert lesen koennen, ohne Zugriff auf
 * alles zu bekommen, was an einer Session haengt.
 *
 * <p>{@code submittedFields} are the field names of the form post this response answers (empty
 * on a first render) - the client's own knowledge of where it just was, for a renderer with more
 * than one page: a {@code failed-attempt} step carries no {@code missingFields}, so the page the
 * attempt was sent from is the page to show again, the same rule the React forms follow.
 */
public record WebToolRenderContext(
        String toolId,
        String step,
        Map<String, JsonNode> stepData,
        Map<String, JsonNode> demo,
        String error,
        OrchestratorSettings settings,
        Set<String> submittedFields
) {
}
