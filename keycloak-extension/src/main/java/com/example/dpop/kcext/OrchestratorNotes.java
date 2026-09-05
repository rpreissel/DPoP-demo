package com.example.dpop.kcext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.authenticators.util.AcrStore;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Where this plugin keeps state across a single flow run (docs/ideen/web-keycloak-kanal.md #10) -
 * one place for every auth-/user-session note key and the small bits of JSON bookkeeping around
 * them, so OrchestratorAuthenticator and OrchestratorUpdateAuthenticator agree on the same shapes
 * without duplicating parsing logic.
 */
final class OrchestratorNotes {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** This flow run's channelSessionId, derived once and reused by every step of the same run. */
    static final String CHANNEL_SESSION_ID = "orchestrator_channel_session_id";
    /** Which pending step this authenticator is currently showing - "select" or "tool". */
    static final String PENDING_KIND = "orchestrator_pending_kind";
    static final String PENDING_TOOL_ID = "orchestrator_pending_tool_id";
    static final String PENDING_TOOL_SESSION_ID = "orchestrator_pending_tool_session_id";
    /** JSON array of {nativeToolId, amrSourceId} - the full, current set (docs/ideen/web-keycloak-kanal.md #9: no delta). */
    static final String NATIVE_AMR = "orchestrator_native_amr";
    /** Set once restoreData was already submitted this flow run, so a later resume doesn't resend it. */
    static final String RESTORE_SUBMITTED = "orchestrator_restore_submitted";

    /**
     * The peer-auth anchor this flow run established - Keycloak's own (eventual) UserSessionModel
     * id, present even before Keycloak has a `sub` (see {@code ChannelSession.kcSessionId}'s own
     * doc on the orchestrator side for why initial-login and step-up don't need separate values
     * here). Set by whichever authenticator first resolves it (Resume, or the login/update
     * authenticators themselves) and copied onto the LOGIN event's details so
     * {@link OrchestratorRestoreDataListener}, which only sees that event, can rebuild it.
     */
    static final String ANCHOR_VALUE = "orchestrator_anchor_value";

    /**
     * The one place that records this flow run's resolved anchor - both as an auth-session note
     * (for {@code action()}'s later request, see {@link OrchestratorAuthenticator#anchor}) and as
     * an event detail (for {@link OrchestratorRestoreDataListener}, which only ever sees the LOGIN
     * event and nothing else). Every authenticator that resolves an anchor calls this instead of
     * writing these notes/details itself, so they can never drift out of step with each other.
     */
    static void recordAnchor(AuthenticationFlowContext context, String anchorValue) {
        context.getAuthenticationSession().setAuthNote(ANCHOR_VALUE, anchorValue);
        context.getEvent().detail(ANCHOR_VALUE, anchorValue);
    }

    static String readAnchor(AuthenticationFlowContext context) {
        return context.getAuthenticationSession().getAuthNote(ANCHOR_VALUE);
    }

    /**
     * Whether {@link #recordAnchor} has already run this flow. {@link OrchestratorResumeAuthenticator}
     * runs first and unconditionally decides this - records one if it found a valid identity
     * cookie (step-up), or leaves it unrecorded (anonymous flow) otherwise. Every later
     * authenticator that touches a channel checks this INSTEAD of re-resolving the anchor itself:
     * if true, {@link #readAnchor} already has the answer; if false, THIS is the first to
     * establish it, as an initial login.
     */
    static boolean anchorEstablished(AuthenticationFlowContext context) {
        return readAnchor(context) != null;
    }

    /** Copied into the UserSessionModel automatically at session creation (docs/ideen/web-keycloak-kanal.md #10). */
    static final String USER_SESSION_NOTE_ACR = "orchestrator_acr";
    static final String USER_SESSION_NOTE_AMR = "orchestrator_amr";
    /** Written directly onto an existing UserSessionModel by the end-of-flow restore-data hook - see OrchestratorEventListener. */
    static final String USER_SESSION_NOTE_RESTORE_DATA = "orchestrator_restore_data";

    /** The orchestrator accountId, once known - a durable Keycloak user attribute, read back on every later step-up. */
    static final String USER_ATTR_ACCOUNT_ID = "orchestratorAccountId";

    private OrchestratorNotes() {
    }

    /**
     * Always the same value for the whole flow run (docs/ideen/web-keycloak-kanal.md #6: "immer
     * insert, nie find"). Deliberately keyed on the per-CLIENT {@link AuthenticationSessionModel}'s
     * own tab id, not {@link AuthenticationSessionModel#getParentSession()}'s id: Keycloak reuses
     * the same {@code RootAuthenticationSessionModel} (and so the same parent id) across separate
     * authorization requests from an already-SSO'd browser - a step-up request right after a login
     * would otherwise derive the SAME channelSessionId as that completed login run and collide with
     * its stored kc-anchor (KcChannelAccessGuard's BINDING_MISMATCH). The tab id is fresh per
     * authorization request even when the root session is reused, which is exactly "fresh, unique
     * per flow run" here.
     */
    static String channelSessionId(AuthenticationFlowContext context) {
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        String existing = authSession.getAuthNote(CHANNEL_SESSION_ID);
        if (existing != null) return existing;
        String tabId = authSession.getTabId();
        String derived = UUID.nameUUIDFromBytes(("kc-auth-session-tab:" + tabId).getBytes()).toString();
        authSession.setAuthNote(CHANNEL_SESSION_ID, derived);
        return derived;
    }

    static List<OrchestratorClient.AmrEntry> nativeAmr(AuthenticationFlowContext context) {
        String raw = context.getAuthenticationSession().getAuthNote(NATIVE_AMR);
        List<OrchestratorClient.AmrEntry> entries = new ArrayList<>();
        if (raw == null || raw.isBlank()) return entries;
        try {
            for (JsonNode node : MAPPER.readTree(raw)) {
                entries.add(new OrchestratorClient.AmrEntry(node.get("nativeToolId").asText(), node.get("amrSourceId").asText()));
            }
        } catch (Exception ignored) {
            // Corrupt note (should not happen, we only ever write via appendNativeAmr) - treat as empty.
        }
        return entries;
    }

    /** Appends or replaces (by nativeToolId) one native proof - the full, current set is always resent, never a delta. */
    static void appendNativeAmr(AuthenticationFlowContext context, String nativeToolId, String amrSourceId) {
        List<OrchestratorClient.AmrEntry> entries = new ArrayList<>(nativeAmr(context));
        entries.removeIf(e -> e.nativeToolId().equals(nativeToolId));
        entries.add(new OrchestratorClient.AmrEntry(nativeToolId, amrSourceId));
        ArrayNode array = MAPPER.createArrayNode();
        for (OrchestratorClient.AmrEntry entry : entries) {
            array.addObject().put("nativeToolId", entry.nativeToolId()).put("amrSourceId", entry.amrSourceId());
        }
        context.getAuthenticationSession().setAuthNote(NATIVE_AMR, array.toString());
    }

    /**
     * The SSO UserSessionModel this browser already carries a valid identity cookie for, if any -
     * the "Nutzer bekannt" branch of Section 2's anchor model. {@code context.getSession().getContext()
     * .getUserSession()} looks like the obvious way to ask this, but it is NOT: Keycloak only ever
     * populates that field once, at the very end of a successful flow ({@code AuthenticationProcessor
     * .attachSession}) - it reads back as null through every authenticate()/action() call of the
     * flow that is supposedly setting it up. The one reliable way to ask "is there already a valid
     * SSO session" mid-flow is the same one {@code auth-cookie} itself uses to answer it: verify the
     * raw identity cookie directly via {@link AuthenticationManager#authenticateIdentityCookie}.
     *
     * Called ONLY from {@link OrchestratorResumeAuthenticator}, which runs first in the flow and
     * records the outcome via {@link #recordAnchor} right away - every LATER authenticator just
     * checks {@link #anchorEstablished} instead of asking this question (or verifying the cookie)
     * itself again.
     */
    static UserSessionModel resolveExistingUserSession(AuthenticationFlowContext context) {
        AuthenticationManager.AuthResult authResult =
                AuthenticationManager.authenticateIdentityCookie(context.getSession(), context.getRealm(), true);
        return authResult == null ? null : authResult.session();
    }

    static Long accountId(UserModel user) {
        String value = user == null ? null : user.getFirstAttribute(USER_ATTR_ACCOUNT_ID);
        return value == null || value.isBlank() ? null : Long.parseLong(value);
    }

    // The same numeric-level-to-orchestrator-ACR mapping infra/tofu/keycloak/main.tf's per-execution
    // "targetAcr" config values encode individually (Condition-LoA level 1 -> "loa1", level 2 ->
    // "loa2") - duplicated here as a constant map because it must be readable BEFORE the specific
    // subflow that owns that level ever runs, see requestedAcr's own doc.
    private static final Map<Integer, String> LOA_TO_ACR = Map.of(1, "loa1", 2, "loa2");

    /**
     * Keycloak's own requested level of authentication for this top-level flow (e.g. from
     * {@code acr_values=2}), translated to the orchestrator's ACR string - independent of which
     * Condition-LoA subflow happens to be running right now. Every authenticator that touches a
     * channel should raise its floor to THIS, not just its own subflow's static level: a channel
     * whose floor only reaches "loa1" because that is all LoA-1's own report knows about finishes
     * the entry journey as soon as a loa1-sufficient proof arrives - even when the browser actually
     * asked for loa2 and Keycloak's OWN conditional-level-of-authentication fully intends to run
     * LoA-2 right afterward. By then there is no active journey left for LoA-2's later, higher
     * floor to apply to (KcSelectMethodStrategy.afterProof already returned Decision.Authenticated),
     * so the step-up silently never happens. Null if Keycloak has no requested level at all (a plain
     * login with no acr_values) or the level isn't one of ours - callers fall back to their own
     * static config in that case, same as before this existed.
     */
    static String requestedAcr(AuthenticationFlowContext context) {
        AcrStore acrStore = new AcrStore(context.getSession(), context.getAuthenticationSession());
        int requested = acrStore.getRequestedLevelOfAuthentication(context.getTopLevelFlow());
        return LOA_TO_ACR.get(requested);
    }
}
