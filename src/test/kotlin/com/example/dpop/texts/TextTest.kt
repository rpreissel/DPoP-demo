package com.example.dpop.texts

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
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
            Text("A").id shouldBe "a-559aea"
        }

        then("it leaves as a reference - never as its source wording, once a bundle words it") {
            TextBundle("app") // loaded as the running application loads it
            val wire = json.writeValueAsString(Text("Weiter"))
            wire shouldBe """{"key":"${Text("Weiter").id}"}"""
        }

        then("while no bundle words it yet, the template travels along - the client shows it instead of the key") {
            val wire = json.writeValueAsString(Text("Retry-Limit erreicht: {grund}", "grund" to Text("TAN nie übersetzt"), "versuche" to 3))
            wire shouldBe """{"key":"${Text("Retry-Limit erreicht: {grund}").id}","args":{"versuche":"3"},""" +
                """"texts":{"grund":[{"key":"${Text("TAN nie übersetzt").id}","template":"TAN nie übersetzt"}]},"template":"Retry-Limit erreicht: {grund}"}"""
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
