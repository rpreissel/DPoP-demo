package com.example.dpop.kcext.grant;

import com.nimbusds.jwt.JWTClaimsSet;
import java.util.Date;

/**
 * The time rules of an account assertion (ADR-9): not expired, and short-lived.
 *
 * The orchestrator issues assertions that live exactly {@link #MAX_LIFETIME_SECONDS}
 * ({@code KcTokenProvider.ASSERTION_TTL_SECONDS}). Only a check on THIS side makes that a
 * property of the grant rather than of one well-behaved caller - a leaked account key could
 * otherwise mint an assertion valid for a year. {@code iat} is required for the same reason:
 * without it there is no lifetime to measure.
 *
 * Its own class rather than a method on {@link AccountTokenGrantType}: the grant extends a
 * Keycloak base class that is compileOnly, so a test could not even load it.
 */
final class AccountAssertionTimes {

    /** exp - iat, at most. */
    static final long MAX_LIFETIME_SECONDS = 60;

    // Generous like kc.peer-auth.max-clock-skew-seconds on the orchestrator side (application-
    // keycloak.yml) - same podman-machine clock-drift environment, opposite direction.
    static final long MAX_CLOCK_SKEW_SECONDS = 300;

    private AccountAssertionTimes() {
    }

    static boolean acceptable(JWTClaimsSet claims, long nowMillis) {
        Date exp = claims.getExpirationTime();
        Date iat = claims.getIssueTime();
        if (exp == null || iat == null) {
            return false;
        }
        long skew = MAX_CLOCK_SKEW_SECONDS * 1000;
        if (nowMillis - skew > exp.getTime() || iat.getTime() > nowMillis + skew) {
            return false;
        }
        return exp.getTime() - iat.getTime() <= MAX_LIFETIME_SECONDS * 1000;
    }
}
