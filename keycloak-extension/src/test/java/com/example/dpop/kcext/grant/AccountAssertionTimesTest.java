package com.example.dpop.kcext.grant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jwt.JWTClaimsSet;
import java.util.Date;
import org.junit.jupiter.api.Test;

/**
 * Die Zeitregeln der Account-Assertion (ADR-9): nicht abgelaufen, und hoechstens 60 Sekunden
 * gueltig - unabhaengig davon, was der Aussteller hineinschreibt.
 */
class AccountAssertionTimesTest {

    private static final long NOW = 1_800_000_000_000L;

    @Test
    void frischeAssertionMitSechzigSekundenGilt() {
        assertTrue(AccountAssertionTimes.acceptable(claims(NOW, NOW + 60_000), NOW));
    }

    @Test
    void langlebigeAssertionWirdAbgelehntAuchWennSieNochNichtAbgelaufenIst() {
        // Ein abgeflossener Account-Schluessel darf keine Assertion fuer ein Jahr ausstellen koennen.
        assertFalse(AccountAssertionTimes.acceptable(claims(NOW, NOW + 61_000), NOW));
        assertFalse(AccountAssertionTimes.acceptable(claims(NOW, NOW + 365L * 24 * 3600 * 1000), NOW));
    }

    @Test
    void ohneIatGibtEsKeineMessbareLebensdauer() {
        JWTClaimsSet ohneIat = new JWTClaimsSet.Builder().expirationTime(new Date(NOW + 30_000)).build();
        assertFalse(AccountAssertionTimes.acceptable(ohneIat, NOW));
    }

    @Test
    void abgelaufenJenseitsDerUhrentoleranzWirdAbgelehnt() {
        assertFalse(AccountAssertionTimes.acceptable(claims(NOW - 400_000, NOW - 340_000), NOW));
        // Innerhalb der 300 Sekunden Toleranz gilt sie noch.
        assertTrue(AccountAssertionTimes.acceptable(claims(NOW - 100_000, NOW - 40_000), NOW));
    }

    @Test
    void iatWeitInDerZukunftWirdAbgelehnt() {
        assertFalse(AccountAssertionTimes.acceptable(claims(NOW + 400_000, NOW + 430_000), NOW));
    }

    private static JWTClaimsSet claims(long iat, long exp) {
        return new JWTClaimsSet.Builder().issueTime(new Date(iat)).expirationTime(new Date(exp)).build();
    }
}
