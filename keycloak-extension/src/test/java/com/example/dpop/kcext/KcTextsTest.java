package com.example.dpop.kcext;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The texts the Keycloakify theme gets as a plain map (docs/ideen/keycloakify-statt-freemarker.md). */
class KcTextsTest {

    @Test
    void browserGetsOnlyThisExtensionsOwnIdsWithPlaceholdersLeftForIt() {
        Properties messages = new Properties();
        messages.setProperty(KcText.idOf("Weiter"), "Continue");
        messages.setProperty(KcText.idOf("Demo-Code: {wert}"), "Demo code: {wert}");
        // Keycloak's own messages of the same theme hierarchy stay out.
        messages.setProperty("doLogIn", "Sign In");
        messages.setProperty("loginTitle", "Sign in to {0}");

        assertEquals(
                Map.of(KcText.idOf("Weiter"), "Continue", KcText.idOf("Demo-Code: {wert}"), "Demo code: {wert}"),
                KcTexts.ownTexts(messages));
    }
}
