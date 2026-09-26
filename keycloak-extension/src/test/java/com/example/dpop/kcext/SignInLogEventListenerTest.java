package com.example.dpop.kcext;

import org.junit.jupiter.api.Test;
import org.keycloak.events.EventType;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which Keycloak events reach the orchestrator's sign-in log (review 2026-09-26, T-1): only the
 * logout of one of its own users. The after-commit, fire-and-forget delivery is the listener's own
 * structure ({@code enlistAfterCompletion}); this pins the filter in front of it.
 */
class SignInLogEventListenerTest {

    private static final String COMPONENT = "orch-accounts";
    private static final String OUR_USER = "f:" + COMPONENT + ":42";

    @Test
    void aLogoutOfOneOfOurUsersIsReportedForItsAccount() {
        assertEquals(OptionalLong.of(42), SignInLogEventListener.accountToReport(EventType.LOGOUT, OUR_USER, "kc-session", COMPONENT));
    }

    @Test
    void anyOtherEventIsNot() {
        assertEquals(OptionalLong.empty(), SignInLogEventListener.accountToReport(EventType.LOGIN, OUR_USER, "kc-session", COMPONENT));
        assertEquals(OptionalLong.empty(), SignInLogEventListener.accountToReport(EventType.LOGOUT_ERROR, OUR_USER, "kc-session", COMPONENT));
    }

    @Test
    void aUserKeycloakKeepsItselfHasNoAccountToLogFor() {
        // Keycloak's own admin: a local user, no storage prefix.
        assertEquals(OptionalLong.empty(), SignInLogEventListener.accountToReport(EventType.LOGOUT, "8f14e45f-ceea-467a-a866-051f5a42c8b1", "kc-session", COMPONENT));
        // A user of some other federation.
        assertEquals(OptionalLong.empty(), SignInLogEventListener.accountToReport(EventType.LOGOUT, "f:ldap:42", "kc-session", COMPONENT));
    }

    @Test
    void withoutSessionOrWithAMalformedIdNothingIsReported() {
        assertEquals(OptionalLong.empty(), SignInLogEventListener.accountToReport(EventType.LOGOUT, OUR_USER, null, COMPONENT));
        assertEquals(OptionalLong.empty(), SignInLogEventListener.accountToReport(EventType.LOGOUT, null, "kc-session", COMPONENT));
        assertEquals(OptionalLong.empty(), SignInLogEventListener.accountToReport(EventType.LOGOUT, "f:" + COMPONENT + ":not-a-number", "kc-session", COMPONENT));
    }
}
