package com.example.dpop.kcext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.authenticators.util.AcrStore;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
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
public final class OrchestratorNotes {

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
     * Copied into the UserSessionModel automatically at session creation (docs/ideen/web-keycloak-kanal.md #10).
     * Public: also written directly by {@link com.example.dpop.kcext.grant.AccountTokenGrantType}
     * (DPoP-demo-xso) for the App-channel custom grant's own session, which never runs through
     * {@link OrchestratorAuthenticator} - same note keys, same {@link OrchestratorAcrAmrMapper}
     * reads them for either origin.
     */
    public static final String USER_SESSION_NOTE_ACR = "orchestrator_acr";
    public static final String USER_SESSION_NOTE_AMR = "orchestrator_amr";
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
        return channelSessionId(context.getAuthenticationSession());
    }

    /**
     * Same as {@link #channelSessionId(AuthenticationFlowContext)}, taking the
     * {@link AuthenticationSessionModel} directly - {@code RequiredActionContext} (unlike
     * {@code AuthenticationFlowContext}) has no unifying supertype with it, but both expose the
     * same {@code getAuthenticationSession()}, so this is the one shared entry point
     * {@link OrchestratorManageMethodsRequiredAction} uses too.
     */
    static String channelSessionId(AuthenticationSessionModel authSession) {
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
     * Called from {@link OrchestratorResumeAuthenticator#authenticate} (mid-flow, to decide
     * whether this is a step-up) and again from its own {@code onTopFlowSuccess} (end-of-flow, to
     * resolve which session RestoreData should be bound to) - the two ends of that class's own
     * RestoreData lifecycle.
     */
    static UserSessionModel resolveExistingUserSession(KeycloakSession session, RealmModel realm) {
        AuthenticationManager.AuthResult authResult =
                AuthenticationManager.authenticateIdentityCookie(session, realm, true);
        return authResult == null ? null : authResult.session();
    }

    /**
     * The Section 6 end-of-flow RestoreData hook (docs/ideen/web-keycloak-kanal.md #6) - called
     * from {@link OrchestratorResumeAuthenticator#onTopFlowSuccess}, which fires once, at the true
     * end of the WHOLE top-level flow (after LoA-1/LoA-2, whichever ran, are already done) -
     * replacing the separate OrchestratorRestoreDataListener event listener entirely. Takes
     * {@code session}/{@code authSession} directly rather than an {@link AuthenticationFlowContext}
     * because {@code onTopFlowSuccess} only ever hands back an {@code AuthenticationFlowModel}, not
     * a context - the same {@code session.getContext().getAuthenticationSession()} pattern
     * Keycloak's own {@code ConditionalLoaAuthenticator.onTopFlowSuccess} uses.
     *
     * No UserSessionModel exists yet at this point for a first-time login (Keycloak only creates
     * one once every authenticator has already succeeded), but {@code setUserSessionNote} works
     * regardless - Keycloak copies every such note onto the real UserSessionModel once it exists
     * ({@code TokenManager.attachAuthenticationSession}), so writing it here has the same effect as
     * writing it directly on the session later would.
     */
    static void stashRestoreDataAtFlowEnd(KeycloakSession session, AuthenticationSessionModel authSession, OrchestratorClient client, Logger log) {
        String channelSessionId = authSession.getAuthNote(CHANNEL_SESSION_ID);
        if (channelSessionId == null) return; // Not a Keycloak-channel flow run.
        try {
            UserSessionModel existing = resolveExistingUserSession(session, authSession.getParentSession().getRealm());
            String durableSessionId = existing != null ? existing.getId() : authSession.getParentSession().getId();
            String restoreData = client.restoreData(channelSessionId, durableSessionId);
            if (restoreData != null) {
                authSession.setUserSessionNote(USER_SESSION_NOTE_RESTORE_DATA, restoreData);
            }
        } catch (Exception e) {
            // Best-effort: a missed RestoreData write only means a later step-up starts without a
            // running start (docs/ideen/web-keycloak-kanal.md #6), never a broken login.
            log.warnf(e, "Failed to fetch/stash RestoreData for channel %s", channelSessionId);
        }
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
