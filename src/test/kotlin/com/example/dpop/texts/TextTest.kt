package com.example.dpop.texts

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import tools.jackson.module.kotlin.jacksonObjectMapper

class TextTest : BehaviorSpec({

    val json = jacksonObjectMapper()

    // These templates are test-only, so not in the application's catalog the runtime guard checks.
    val guard = Text.onWire
    beforeSpec { Text.onWire = null }
    afterSpec { Text.onWire = guard }

    given("a text") {
        then("the same template is the same id, whatever the values") {
            Text("Dieser {typ}-Wert", "typ" to "email").id shouldBe Text("Dieser {typ}-Wert", "typ" to "kvnr").id
            Text("A").id.length shouldBe 12
        }

        then("it leaves as a reference - never as its source wording") {
            val wire = json.writeValueAsString(Text("Retry-Limit erreicht: {grund}", "grund" to Text("TAN ungültig"), "versuche" to 3))
            wire shouldBe """{"key":"${Text("Retry-Limit erreicht: {grund}").id}","args":{"versuche":"3"},"texts":{"grund":[{"key":"${Text("TAN ungültig").id}"}]}}"""
            wire shouldNotContain "Retry"
        }

        then("a list of texts is one translated argument") {
            Text("Faktoren: {f}", "f" to listOf(Text("Besitz"), Text("Wissen"))).texts.getValue("f").size shouldBe 2
        }
    }

    given("placeholders") {
        then("are the {name}s of a wording") {
            Text.placeholdersOf("Die {methods} decken {n} ab, {x y} nicht") shouldBe setOf("methods", "n")
        }
    }
})
