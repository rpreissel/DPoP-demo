package com.example.dpop.kcext;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
import java.util.Objects;

/**
 * Talks to the orchestrator's one kc-facade endpoint (docs/05-api.md Abschnitt 3) -
 * plain {@code java.net.http.HttpClient}, same convention AuthApiClient in auth-sandbox-2's own
 * keycloak-extension follows. Every call carries a freshly signed peer-auth assertion (docs/12-entscheidungen.md
 * ADR-7); there is no separate bearer token to fetch first, unlike a normal
 * OIDC client credentials call - Keycloak IS the caller identity here.
 */
final class OrchestratorClient {

    /**
     * Unbekannte Felder werden ueberlesen. Das ist keine Nachlaessigkeit, sondern die
     * Gegenrichtung zur Compile-Pruefung: Diese Extension laeuft in einem eigenen Image und muss
     * gegen einen neueren Orchestrator weiterarbeiten. Ein neu HINZUGEKOMMENES Feld darf sie nicht
     * stoppen; ein UMBENANNTES oder ENTFERNTES faellt weiterhin beim Compilieren auf.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http = HttpClient.newHttpClient();
    private final String baseUrl;
    private final PeerAuthAssertionSigner signer;
    private final OrchestratorResponseVerifier verifier;

    OrchestratorClient(String baseUrl, String issuer, String audience, com.nimbusds.jose.jwk.ECKey signingKey) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.signer = new PeerAuthAssertionSigner(issuer, audience, signingKey);
        // The orchestrator answers as the audience of our assertions, addressed to their issuer (us).
        this.verifier = OrchestratorResponseVerifier.forOrchestrator(this.baseUrl, audience, issuer);
    }

    /**
     * PATCH .../kc/channels/{channelSessionId} - upsert semantics (docs/05-api.md
     * Abschnitt 3). Signed with {@code channelSessionId} itself as the peer-auth anchor (docs/02-domaenenmodell.md
     * Abschnitt 1) - unique per flow run, so two concurrent flows (e.g. two tabs
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
     * GET .../kc/channels/{channelSessionId}/restore-data - end-of-flow lifecycle hook (docs/05-api.md
     * Abschnitt 3). Signed with {@code channelSessionId} as the anchor, same as every
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

    /**
     * POST .../channels/{channelSessionId}/enrollments - starts the MANAGE_AUTH_METHODS journey
     * on an already-AUTHENTICATED channel (docs/05-api.md, "Anmeldeverfahren verwalten im
     * Web-Kanal"). Already facade-neutral like {@link #activateTool}/{@link #patchTool} below - guarded by the
     * same {@code @BindingKey} resolution (DpopBindingKeyResolver.kt), which accepts a peer-auth
     * assertion exactly like a DPoP proof. No request body: {@code channelSessionId} alone
     * addresses which channel, same as {@link #activateTool}.
     */
    ChannelResponse startEnrollments(String channelSessionId) throws IOException, InterruptedException {
        String path = "/orchestrator/api/v1/channels/" + channelSessionId + "/enrollments";
        return ChannelResponse.from(send("POST", path, channelSessionId, MAPPER.createObjectNode()));
    }

    /**
     * GET .../channels/{channelSessionId}/methods - the active-methods list "manage" also needs
     * to be a real management screen, not just an add-a-method form (docs/05-api.md,
     * "Anmeldeverfahren verwalten im Web-Kanal"). Same facade-neutral guard as every other call here.
     */
    List<MethodView> getMethods(String channelSessionId) throws IOException, InterruptedException {
        String path = "/orchestrator/api/v1/channels/" + channelSessionId + "/methods";
        JsonNode response = send("GET", path, channelSessionId, null);
        List<MethodView> methods = new ArrayList<>();
        response.path("methods").forEach(m -> methods.add(MethodView.from(m)));
        return methods;
    }

    /**
     * DELETE .../channels/{channelSessionId}/methods/{methodInstanceId} - deactivates one active
     * method instance. Like enrollment, this can come back asking for a loa2 step-up first
     * instead of finishing directly - same {@link ChannelResponse} shape, same dispatch loop.
     */
    ChannelResponse deactivateMethod(String channelSessionId, String methodInstanceId) throws IOException, InterruptedException {
        String path = "/orchestrator/api/v1/channels/" + channelSessionId + "/methods/" + methodInstanceId;
        return ChannelResponse.from(send("DELETE", path, channelSessionId, null));
    }

    /** Same facade-neutral tool endpoints the App channel uses (docs/05-api.md Abschnitt 3). */
    ChannelResponse activateTool(String channelSessionId, String toolId) throws IOException, InterruptedException {
        String path = "/orchestrator/api/v1/channels/" + channelSessionId + "/tools/" + toolId;
        return ChannelResponse.from(send("POST", path, channelSessionId, MAPPER.createObjectNode()));
    }

    /**
     * {@code channelSessionId} is passed purely for signing here - toolSessionId, unlike
     * channelSessionId, isn't self-authorizing (docs/02-domaenenmodell.md Abschnitt 1): this URL
     * carries no channelSessionId of its own for {@code htu} to bind the assertion to, so the
     * anchor claim is the ONLY thing tying this call to the right channel.
     */
    ChannelResponse patchTool(String channelSessionId, String toolSessionId, String toolId, Map<String, String> fields) throws IOException, InterruptedException {
        String path = "/orchestrator/api/v1/tools/" + toolSessionId + "/" + toolId;
        ObjectNode body = MAPPER.createObjectNode();
        fields.forEach(body::put);
        return ChannelResponse.from(send("PATCH", path, channelSessionId, body));
    }

    /** DELETE .../tools/{toolSessionId}/{toolId} - declines the running tool ("Abbrechen"). */
    ChannelResponse abandonTool(String channelSessionId, String toolSessionId, String toolId) throws IOException, InterruptedException {
        String path = "/orchestrator/api/v1/tools/" + toolSessionId + "/" + toolId;
        return ChannelResponse.from(send("DELETE", path, channelSessionId, null));
    }

    /** POST .../tools/{toolSessionId}/{toolId}/back - leaves the running tool without declining it ("Zurück"). */
    ChannelResponse backFromTool(String channelSessionId, String toolSessionId, String toolId) throws IOException, InterruptedException {
        String path = "/orchestrator/api/v1/tools/" + toolSessionId + "/" + toolId + "/back";
        return ChannelResponse.from(send("POST", path, channelSessionId, MAPPER.createObjectNode()));
    }

    /**
     * DELETE .../channels/{channelSessionId}/journey - abandons the currently active journey
     * BEFORE any candidate tool was picked (the "select" step, e.g. a loa2 step-up's method
     * choice). Unlike {@link #abandonTool}, which backs out of an already-activated tool, this is
     * the only way out of a select screen: cancelling a sub-journey (like MANAGE_AUTH_METHODS'
     * step-up gate) resumes its parent as AUTHENTICATED, cancelling a top-level one restarts the
     * channel's entry journey from scratch - either way the response's own {@code next} says what
     * to render, same dispatch as every other call here.
     */
    ChannelResponse abandonJourney(String channelSessionId) throws IOException, InterruptedException {
        String path = "/orchestrator/api/v1/channels/" + channelSessionId + "/journey";
        return ChannelResponse.from(send("DELETE", path, channelSessionId, null));
    }

    /**
     * POST .../channels/{channelSessionId}/answer - the generic yes/no reply every
     * {@code AnswerableState} (next.context=prompt, next.step=confirm) understands, same address
     * the App channel already posts to. {@code answer} is exactly {@code "accept"} or
     * {@code "decline"}.
     */
    ChannelResponse answer(String channelSessionId, String answer) throws IOException, InterruptedException {
        String path = "/orchestrator/api/v1/channels/" + channelSessionId + "/answer";
        ObjectNode body = MAPPER.createObjectNode();
        body.put("answer", answer);
        return ChannelResponse.from(send("POST", path, channelSessionId, body));
    }

    /**
     * Stateless password verify/set for Keycloak's native password credential
     * ({@code OrchestratorStorageProvider}) - unlike every other call above, there is no
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

    /**
     * Reports that Keycloak ended session {@code kcSessionId} of {@code accountId}, for the
     * account's sign-in log (ADR-39, addendum) - the Web channel's logout is Keycloak's own, the
     * orchestrator would not learn of it otherwise. Same anchor convention as the password calls.
     */
    void reportSignOut(long accountId, String kcSessionId) throws IOException, InterruptedException {
        String path = "/orchestrator/api/v1/kc/accounts/" + accountId + "/sign-outs?kcSessionId=" + urlEncode(kcSessionId);
        send("POST", path, String.valueOf(accountId), null);
    }

    /** See {@link #verifyPassword(long, String)} - same anchor convention. */
    void setPassword(long accountId, String newPassword) throws IOException, InterruptedException {
        String path = "/orchestrator/api/v1/tools/enroll-password/mgmt/" + accountId;
        ObjectNode body = MAPPER.createObjectNode();
        body.put("newPassword", newPassword);
        send("POST", path, String.valueOf(accountId), body);
    }

    /**
     * The account behind a federated user, read through (review 2026-09, P-3) - by id, the peer-auth
     * anchor naming the account like the password endpoints do. {@code null} when there is no such account.
     */
    KcAccount accountById(long accountId) throws IOException, InterruptedException {
        return lookup("/orchestrator/api/v1/kc/accounts/" + accountId, String.valueOf(accountId));
    }

    /** By exact email - never a list; {@code null} when no account holds this address. */
    KcAccount accountByEmail(String email) throws IOException, InterruptedException {
        return lookup("/orchestrator/api/v1/kc/accounts?email=" + urlEncode(email), ACCOUNT_LOOKUP_ANCHOR);
    }

    /** By username ({@code account-<id>} or the email); {@code null} when there is none. */
    KcAccount accountByUsername(String username) throws IOException, InterruptedException {
        return lookup("/orchestrator/api/v1/kc/accounts?username=" + urlEncode(username), ACCOUNT_LOOKUP_ANCHOR);
    }

    /** The peer-auth anchor of a search by address - {@code KcAccountLookupController.LOOKUP_ANCHOR}. */
    private static final String ACCOUNT_LOOKUP_ANCHOR = "account-lookup";

    private KcAccount lookup(String path, String anchor) throws IOException, InterruptedException {
        try {
            return KcAccount.from(send("GET", path, anchor, null));
        } catch (OrchestratorApiException e) {
            if (e.status == 404) return null;
            throw e;
        }
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

        HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        // Before anything in the answer is believed - error or not (review 2026-09, M-9).
        verifier.verify(
                response.headers().firstValue(OrchestratorResponseVerifier.HEADER).orElse(null),
                PeerAuthAssertionSigner.jtiOf(assertion),
                response.statusCode(),
                response.body());
        String answer = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new OrchestratorApiException(response.statusCode(), answer);
        }
        if (answer.isBlank()) {
            return MAPPER.createObjectNode();
        }
        return MAPPER.readTree(answer);
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Mirrors ActiveMethodView (tool_api/Envelope.kt) - id/method/label, nothing more. */
    record MethodView(String id, String method, String label) {
        static MethodView from(JsonNode json) {
            JsonNode labelNode = json.get("label");
            return new MethodView(
                    json.path("id").asText(null),
                    json.path("method").asText(null),
                    labelNode != null && labelNode.isTextual() ? labelNode.asText() : null
            );
        }
    }

    record AmrEntry(String nativeToolId, String amrSourceId) {
    }

    /** Mirrors AmrEntry (docs/05-api.md Abschnitt 3) - just the two stable ids, never method/loa directly. */
    static final class OrchestratorApiException extends IOException {
        final int status;
        final String errorCode;
        /** What the user is told - a text reference, resolved per login language ({@link #message}). */
        final JsonNode text;

        OrchestratorApiException(int status, String body) {
            super("Orchestrator call failed: " + status + " " + body);
            this.status = status;
            String parsedCode = null;
            JsonNode parsedText = null;
            // Field names from the generated contract model, so a rename breaks the compile. Read as
            // a tree rather than as that model: its enum rejects a code this build does not know,
            // and the contract says a client must expect new codes.
            try {
                JsonNode node = MAPPER.readTree(body);
                String errorField = com.example.dpop.kcext.api.model.ErrorResponse.JSON_PROPERTY_ERROR;
                String textField = com.example.dpop.kcext.api.model.ErrorResponse.JSON_PROPERTY_TEXT;
                if (node.hasNonNull(errorField)) parsedCode = node.get(errorField).asText();
                if (node.hasNonNull(textField)) parsedText = node.get(textField);
            } catch (Exception ignored) {
                // Not JSON - no text to show; callers fall back to their own.
            }
            this.errorCode = parsedCode;
            this.text = parsedText;
        }

        /** The error in the login's language, or null when the orchestrator sent no text. */
        String message(org.keycloak.models.KeycloakSession session) {
            return OrchestratorTexts.resolve(session, text);
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
        /**
         * Baut die flache Sicht aus den GENERIERTEN Vertragsmodellen (api/openapi.yaml), statt die
         * Felder von Hand aus dem JSON zu klauben.
         *
         * Der Unterschied ist der Fehlermodus. Vorher lieferte ein umbenanntes Feld
         * {@code path("state").asText(null)} still {@code null}, und der Fehler tauchte drei
         * Schichten spaeter auf. Jetzt bricht der Compiler, sobald der Vertrag sich aendert - das
         * ist der ganze Zweck des Umbaus.
         *
         * <p>ZWEI Felder bleiben bewusst offene JsonNodes: {@code stepData} und {@code demo}. Beide
         * sind per Entwurf offene Beutel, aus denen die Renderer einzelne Schluessel picken. Fuer
         * stepData kommt ein zweiter Grund dazu: Der generierte Union-Typ wirft bei einer Form, die
         * dieser Client noch nicht kennt (siehe ContractModelTest). Diese Extension wird in einem
         * eigenen Container-Image ausgeliefert und muss einen Deploy-Versatz gegen einen neueren
         * Orchestrator ueberstehen - ein unbekannter Schritt darf sie nicht zerlegen.
         */
        static ChannelResponse from(JsonNode json) {
            com.example.dpop.kcext.api.model.ChannelResponse wire;
            try {
                wire = MAPPER.treeToValue(stripOpenBags(json), com.example.dpop.kcext.api.model.ChannelResponse.class);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Antwort des Orchestrators passt nicht zum Vertrag", e);
            }

            Map<String, JsonNode> stepData = new LinkedHashMap<>();
            json.path("stepData").properties().forEach(e -> stepData.put(e.getKey(), e.getValue()));

            // Sibling of stepData, not nested in it (tool_spi/Demo.kt: the caller lifts DEMO_DATA_KEY
            // out into its own top-level block before building the client response) - passed through
            // unchanged to WebToolRenderer, same as stepData, since different tools prefill different keys.
            Map<String, JsonNode> demo = new LinkedHashMap<>();
            json.path("demo").properties().forEach(e -> demo.put(e.getKey(), e.getValue()));

            var channel = wire.getChannel();
            var wireNext = wire.getNext();
            var authData = wire.getAuthData();

            return new ChannelResponse(
                    // Der Vertrag fuehrt die beiden Session-Ids als uuid, die flache Sicht als String -
                    // die Extension reicht sie nur als Pfadsegment weiter und parst sie nie.
                    channel == null ? null : Objects.toString(channel.getChannelSessionId(), null),
                    channel == null ? null : channel.getState(),
                    wireNext == null ? null : new Next(
                            wireNext.getType(),
                            wireNext.getToolId(),
                            wireNext.getContext(),
                            wireNext.getStep(),
                            Objects.toString(wireNext.getToolSessionId(), null)
                    ),
                    stepData,
                    demo,
                    authData == null ? null : authData.getAccountId(),
                    authData == null ? null : authData.getAcr(),
                    authData == null || authData.getAmr() == null ? Map.of() : authData.getAmr()
            );
        }

        /**
         * Entfernt die beiden offenen Beutel, bevor das typisierte Modell sie zu sehen bekommt.
         *
         * Ohne das wuerde der generierte stepData-Union-Typ jede Antwort mit einer unbekannten Form
         * zum Scheitern bringen - obwohl dieser Client den Inhalt ohnehin nur als JsonNode liest.
         */
        private static JsonNode stripOpenBags(JsonNode json) {
            if (!(json instanceof ObjectNode object)) {
                return json;
            }
            ObjectNode copy = object.deepCopy();
            copy.remove("stepData");
            copy.remove("demo");
            return copy;
        }

        List<String> stepDataOptions() {
            List<String> options = new ArrayList<>();
            stepData.getOrDefault("options", MAPPER.createArrayNode()).forEach(n -> options.add(n.asText()));
            return options;
        }

        /** The failed attempt's text reference, if the last attempt failed. */
        JsonNode stepDataError() {
            return stepData.get("error");
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
            // Reached through REGISTER's kc-facade path (DPoP-demo-urt).
            return "orchestrator".equals(type) && ("selectMethod".equals(step) || "selectIdentificationMethod".equals(step));
        }

        boolean isAuthenticated() {
            return "orchestrator".equals(type) && "authenticated".equals(step);
        }

        boolean isConfirm() {
            return "orchestrator".equals(type) && "confirm".equals(step);
        }
    }
}
