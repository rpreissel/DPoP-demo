package com.example.dpop.kcext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Talks to the orchestrator's one kc-facade endpoint (docs/ideen/web-keycloak-kanal.md #6/#10) -
 * plain {@code java.net.http.HttpClient}, same convention AuthApiClient in auth-sandbox-2's own
 * keycloak-extension follows. Every call carries a freshly signed peer-auth assertion (docs/ideen/
 * web-keycloak-kanal.md #3); there is no separate bearer token to fetch first, unlike a normal
 * OIDC client credentials call - Keycloak IS the caller identity here.
 */
final class OrchestratorClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http = HttpClient.newHttpClient();
    private final String baseUrl;
    private final PeerAuthAssertionSigner signer;

    OrchestratorClient(String baseUrl, String issuer, String audience) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.signer = new PeerAuthAssertionSigner(issuer, audience);
    }

    /**
     * PATCH .../kc/channels/{channelSessionId} - upsert semantics (docs/ideen/web-keycloak-kanal.md
     * #6). Signed with {@code channelSessionId} itself as the peer-auth anchor (docs/ideen/
     * web-keycloak-kanal.md #4) - unique per flow run, so two concurrent flows (e.g. two tabs
     * stepping up the same SSO session at once) never share an anchor value even though they'd
     * share the same underlying UserSession. {@code durableKcSessionId} is a SEPARATE, optional
     * value - Keycloak's actual (eventual) UserSessionModel id - carried in the body only when
     * {@code restoreData} is present, so the server can verify that token was minted for THIS
     * browser's real, durable identity, not just for this one flow run's anchor.
     */
    ChannelResponse upsertChannel(
            String channelSessionId,
            Long accountId,
            String targetAcr,
            List<AmrEntry> amr,
            String restoreData,
            String durableKcSessionId,
            List<String> availableTools,
            String intent
    ) throws IOException, InterruptedException {
        String path = "/orchestrator/api/v1/kc/channels/" + channelSessionId;
        ObjectNode body = MAPPER.createObjectNode();
        if (accountId != null) body.put("accountId", accountId);
        if (targetAcr != null) body.put("targetAcr", targetAcr);
        // Only meaningful on this channel's first call too, same as availableTools above
        // (docs/05-api.md #"POST /app/channels: intent-Parameter", kc-facade's own counterpart).
        // Omitted (null/blank) means the facade's own default (kc_select_method, i.e. today's
        // login/step-up behaviour) - only the initial-login OrchestratorAuthenticator ever sends
        // this, never Resume/Update, which only ever continue an already-existing channel.
        if (intent != null && !intent.isBlank()) body.put("intent", intent);
        // Only meaningful on this channel's first call (KcChannelService.upsertChannel creates the
        // channel then, never on a later resume) - sent every time regardless, same as the App
        // channel's own `availableTools` (frontend/src/tools/registry.ts's knownToolIds), since this
        // client has no cheap way to know in advance whether the channel already exists.
        if (availableTools != null && !availableTools.isEmpty()) {
            ArrayNode toolsArray = body.putArray("availableTools");
            availableTools.forEach(toolsArray::add);
        }
        if (amr != null && !amr.isEmpty()) {
            ArrayNode amrArray = body.putArray("amr");
            for (AmrEntry entry : amr) {
                ObjectNode entryNode = amrArray.addObject();
                entryNode.put("nativeToolId", entry.nativeToolId());
                entryNode.put("amrSourceId", entry.amrSourceId());
            }
        }
        if (restoreData != null) {
            body.put("restoreData", restoreData);
            body.put("kcSessionId", durableKcSessionId);
        }
        JsonNode response = send("PATCH", path, channelSessionId, body);
        return ChannelResponse.from(response);
    }

    /**
     * GET .../kc/channels/{channelSessionId}/restore-data - end-of-flow lifecycle hook (docs/ideen/
     * web-keycloak-kanal.md #6). Signed with {@code channelSessionId} as the anchor, same as every
     * other call; {@code durableKcSessionId} (Keycloak's actual UserSessionModel id) is carried
     * separately as a query param purely because it's what the RETURNED token gets bound to - it
     * has nothing to do with THIS call's own authorization, which the anchor alone already covers.
     */
    String restoreData(String channelSessionId, String durableKcSessionId) throws IOException, InterruptedException {
        String path = "/orchestrator/api/v1/kc/channels/" + channelSessionId + "/restore-data?kcSessionId=" + urlEncode(durableKcSessionId);
        JsonNode response = send("GET", path, channelSessionId, null);
        JsonNode restoreData = response.path("restoreData");
        return restoreData.isTextual() ? restoreData.asText() : null;
    }

    /** Same facade-neutral tool endpoints the App channel uses (docs/ideen/web-keycloak-kanal.md #6). */
    ChannelResponse activateTool(String channelSessionId, String toolId) throws IOException, InterruptedException {
        String path = "/orchestrator/api/v1/channels/" + channelSessionId + "/tools/" + toolId;
        return ChannelResponse.from(send("POST", path, channelSessionId, MAPPER.createObjectNode()));
    }

    /**
     * {@code channelSessionId} is passed purely for signing here - toolSessionId, unlike
     * channelSessionId, isn't self-authorizing (docs/ideen/web-keycloak-kanal.md #4): this URL
     * carries no channelSessionId of its own for {@code htu} to bind the assertion to, so the
     * anchor claim is the ONLY thing tying this call to the right channel.
     */
    ChannelResponse patchTool(String channelSessionId, String toolSessionId, String toolId, Map<String, String> fields) throws IOException, InterruptedException {
        String path = "/orchestrator/api/v1/tools/" + toolSessionId + "/" + toolId;
        ObjectNode body = MAPPER.createObjectNode();
        fields.forEach(body::put);
        return ChannelResponse.from(send("PATCH", path, channelSessionId, body));
    }

    ChannelResponse abandonTool(String channelSessionId, String toolSessionId, String toolId) throws IOException, InterruptedException {
        String path = "/orchestrator/api/v1/tools/" + toolSessionId + "/" + toolId;
        return ChannelResponse.from(send("DELETE", path, channelSessionId, null));
    }

    /**
     * Stateless password verify/set for Keycloak's native password credential
     * ({@code OrchestratorPasswordStorageProvider}) - unlike every other call above, there is no
     * Channel/ToolSession here: the account id is already known (Keycloak's own
     * {@code orchestratorAccountId} user attribute), so it goes straight in the URL path, which
     * {@code htu} already binds. The peer-auth assertion's {@code channel_anchor} claim (normally
     * a channelSessionId) is repurposed to carry the account id instead - checked explicitly by
     * the orchestrator's {@code MgmtPasswordController} against the same path value.
     */
    boolean verifyPassword(long accountId, String password) throws IOException, InterruptedException {
        String path = "/orchestrator/api/v1/tools/auth-password/mgmt/" + accountId;
        ObjectNode body = MAPPER.createObjectNode();
        body.put("password", password);
        JsonNode response = send("POST", path, String.valueOf(accountId), body);
        return response.path("valid").asBoolean(false);
    }

    /** See {@link #verifyPassword(long, String)} - same anchor convention. */
    void setPassword(long accountId, String newPassword) throws IOException, InterruptedException {
        String path = "/orchestrator/api/v1/tools/enroll-password/mgmt/" + accountId;
        ObjectNode body = MAPPER.createObjectNode();
        body.put("newPassword", newPassword);
        send("POST", path, String.valueOf(accountId), body);
    }

    private JsonNode send(String method, String path, String channelSessionId, JsonNode body) throws IOException, InterruptedException {
        String url = baseUrl + path;
        String assertion = signer.sign(method, url, channelSessionId);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + assertion)
                .header("Content-Type", "application/json");
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body.toString());
        builder.method(method, publisher);

        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new OrchestratorApiException(response.statusCode(), response.body());
        }
        if (response.body() == null || response.body().isBlank()) {
            return MAPPER.createObjectNode();
        }
        return MAPPER.readTree(response.body());
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    record AmrEntry(String nativeToolId, String amrSourceId) {
    }

    /** Mirrors AmrEntry (docs/ideen/web-keycloak-kanal.md #6) - just the two stable ids, never method/loa directly. */
    static final class OrchestratorApiException extends IOException {
        final int status;
        final String errorCode;
        final String message;

        OrchestratorApiException(int status, String body) {
            super("Orchestrator call failed: " + status + " " + body);
            this.status = status;
            String parsedCode = null;
            String parsedMessage = body;
            try {
                JsonNode node = MAPPER.readTree(body);
                if (node.hasNonNull("error")) parsedCode = node.get("error").asText();
                if (node.hasNonNull("message")) parsedMessage = node.get("message").asText();
            } catch (Exception ignored) {
                // Not JSON - fall back to the raw body as the message.
            }
            this.errorCode = parsedCode;
            this.message = parsedMessage;
        }
    }

    /** Parsed view of ChannelResponse (tool_api/Envelope.kt) - only the fields this plugin reads. */
    record ChannelResponse(
            String channelSessionId,
            String channelState,
            Next next,
            Map<String, JsonNode> stepData,
            Map<String, JsonNode> demo,
            Long authDataAccountId,
            String authDataAcr,
            Map<String, String> authDataAmr
    ) {
        static ChannelResponse from(JsonNode json) {
            JsonNode channel = json.path("channel");
            JsonNode nextNode = json.path("next");
            Next next = nextNode.isMissingNode() || nextNode.isNull() ? null : Next.from(nextNode);

            Map<String, JsonNode> stepData = new LinkedHashMap<>();
            json.path("stepData").fields().forEachRemaining(e -> stepData.put(e.getKey(), e.getValue()));

            // Sibling of stepData, not nested in it (tool_spi/Demo.kt: the caller lifts DEMO_DATA_KEY
            // out into its own top-level block before building the client response) - passed through
            // unchanged to WebToolRenderer, same as stepData, since different tools prefill different keys.
            Map<String, JsonNode> demo = new LinkedHashMap<>();
            json.path("demo").fields().forEachRemaining(e -> demo.put(e.getKey(), e.getValue()));

            JsonNode authData = json.path("authData");
            Long accountId = authData.hasNonNull("accountId") ? authData.get("accountId").asLong() : null;
            String acr = authData.hasNonNull("acr") ? authData.get("acr").asText() : null;
            Map<String, String> amr = new LinkedHashMap<>();
            authData.path("amr").fields().forEachRemaining(e -> amr.put(e.getKey(), e.getValue().asText()));

            return new ChannelResponse(
                    channel.path("channelSessionId").asText(null),
                    channel.path("state").asText(null),
                    next,
                    stepData,
                    demo,
                    accountId,
                    acr,
                    amr
            );
        }

        List<String> stepDataOptions() {
            List<String> options = new ArrayList<>();
            stepData.getOrDefault("options", MAPPER.createArrayNode()).forEach(n -> options.add(n.asText()));
            return options;
        }

        String stepDataError() {
            JsonNode error = stepData.get("error");
            return error != null && error.isTextual() ? error.asText() : null;
        }
    }

    record Next(String type, String toolId, String context, String step, String toolSessionId) {
        static Next from(JsonNode json) {
            return new Next(
                    json.path("type").asText(null),
                    json.path("toolId").asText(null),
                    json.path("context").asText(null),
                    json.path("step").asText(null),
                    json.path("toolSessionId").asText(null)
            );
        }

        boolean isTool() {
            return "tool".equals(type);
        }

        boolean isSelectMethod() {
            // "selectIdentificationMethod" is Identifying's own step name (docs/04-orchestrierung.md
            // #4/#5, FastAccessState.Identifying.selectionStep) - the same generic multi-option
            // selection screen as every other OfferingState's default "selectMethod", just named
            // differently because it's REGISTER's own identification choice, not an auth choice.
            // Never reachable before DPoP-demo-urt (REGISTER had no kc-facade path), so this arm
            // was previously unexercised by anything actually calling into it.
            return "orchestrator".equals(type) && ("selectMethod".equals(step) || "selectIdentificationMethod".equals(step));
        }

        boolean isAuthenticated() {
            return "orchestrator".equals(type) && "authenticated".equals(step);
        }
    }
}
