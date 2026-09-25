package com.example.dpop.kcext;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The texts a Keycloakify page gets as a plain map (docs/ideen/keycloakify-statt-freemarker.md). */
class KcTextsTest {

    @Test
    void aPageGetsOnlyTheIdsItsThemeNamesWithPlaceholdersLeftForTheBrowser() {
        Properties messages = new Properties();
        messages.setProperty(KcText.idOf("Weiter"), "Continue");
        messages.setProperty(KcText.idOf("Demo-Code: {wert}"), "Demo code: {wert}");
        messages.setProperty(KcText.idOf("Zurück"), "Back");
        messages.setProperty("doLogIn", "Sign In");

        String ids = KcText.idOf("Weiter") + "," + KcText.idOf("Demo-Code: {wert}") + ",000000000000";
        assertEquals(
                Map.of(KcText.idOf("Weiter"), "Continue", KcText.idOf("Demo-Code: {wert}"), "Demo code: {wert}"),
                KcTexts.pick(messages, ids));
    }
}
