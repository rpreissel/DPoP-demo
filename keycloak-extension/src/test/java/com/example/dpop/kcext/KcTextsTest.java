package com.example.dpop.kcext;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The texts a Keycloakify page gets as a plain map (docs/ideen/keycloakify-statt-freemarker.md). */
class KcTextsTest {

    /** The shared samples - the same list pins Text.idOf (TextIdTest) and textId (texts.test.tsx). */
    @Test
    void idIsTheSameRuleAsTheOrchestrators() {
        assertEquals("account-not-found-08a2ef", KcText.idOf("Account not found"));
        assertEquals("journey-trace-laden-fehlgeschlagen-cf9829", KcText.idOf("Journey-Trace laden fehlgeschlagen"));
        assertEquals("noch-anzahl-versuche-fb9887", KcText.idOf("Noch {anzahl} Versuche"));
        assertEquals("groesse-ueber-mass-aeoeue-aeoeue-ss-30656e", KcText.idOf("Größe über Maß – ÄÖÜ äöü ß"));
        assertEquals("loescht-alle-konten-samt-geraeten-038056", KcText.idOf("Löscht alle Konten (samt Geräten, Verfahren und Journey-Trace), setzt alles zurück"));
        assertEquals("caf-73473d", KcText.idOf("Café"));
        assertEquals("e84c53", KcText.idOf("!!!"));
    }


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
