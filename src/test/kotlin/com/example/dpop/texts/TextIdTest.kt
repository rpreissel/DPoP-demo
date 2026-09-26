package com.example.dpop.texts

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * The text id rule, pinned by samples that the frontend (`texts.test.tsx`) and Keycloak
 * (`KcTextTest`) check against the very same expectations - four implementations, one rule.
 */
class TextIdTest : BehaviorSpec({
    given("the shared samples") {
        then("every template gets its readable slug and the first 6 hex digits of its SHA-256") {
            TEXT_ID_SAMPLES.forEach { (template, id) -> Text.idOf(template) shouldBe id }
        }
    }
})

/** Template to expected id - the same list in texts.test.tsx and KcTextTest. */
val TEXT_ID_SAMPLES = mapOf(
    "Account not found" to "account-not-found-08a2ef",
    "Journey-Trace laden fehlgeschlagen" to "journey-trace-laden-fehlgeschlagen-cf9829",
    "Noch {anzahl} Versuche" to "noch-anzahl-versuche-fb9887",
    "Größe über Maß – ÄÖÜ äöü ß" to "groesse-ueber-mass-aeoeue-aeoeue-ss-30656e",
    "Löscht alle Konten (samt Geräten, Verfahren und Journey-Trace), setzt alles zurück" to "loescht-alle-konten-samt-geraeten-038056",
    "Café" to "caf-73473d",
    "!!!" to "e84c53",
)
