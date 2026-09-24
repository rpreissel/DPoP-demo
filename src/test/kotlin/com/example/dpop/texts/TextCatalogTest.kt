package com.example.dpop.texts

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.nio.file.Path

/** Fixtures for the analysis - compiled into the test classes, scanned from there. */
internal object TextCatalogFixtures {
    const val CONSTANT = "Aus einer Konstante"

    fun joined() = Text(
        "Über mehrere " +
            "Zeilen verbunden"
    )

    fun constant() = Text(CONSTANT)

    fun nested() = Text("Außen: {grund}", "grund" to Text("Innen"))

    fun withLambdaArgument(items: List<String>) = Text("Mit Lambda im Argument: {liste}", "liste" to items.map { Text("Eintrag") })

    fun notALiteral(wording: String) = Text(wording)
}

class TextCatalogTest : BehaviorSpec({

    given("the application's classes") {
        then("every Text template is a string literal") {
            TextCatalog.application.problems.shouldBeEmpty()
        }

        then("foreign systems have their own bundle") {
            TextCatalog.bundleOf("com/example/dpop/nect_mock/NectIdent") shouldBe "nect"
            TextCatalog.bundleOf("com/example/dpop/auth_sms/internal/X") shouldBe "app"
        }
    }

    given("the fixtures, compiled") {
        val catalog = TextCatalog.of(Path.of(TextCatalogFixtures::class.java.protectionDomain.codeSource.location.toURI()))
        val templates = catalog.entries.filter { e -> e.locations.any { it.contains("TextCatalogFixtures") } }.map { it.template }

        then("literals joined over several lines are one template, constants count as literals") {
            templates shouldContain "Über mehrere Zeilen verbunden"
            templates shouldContain "Aus einer Konstante"
        }

        then("a template the compiler parks in a local variable is still found") {
            templates shouldContain "Mit Lambda im Argument: {liste}"
        }

        then("a nested text is collected as well") {
            templates shouldContain "Außen: {grund}"
            templates shouldContain "Innen"
        }

        then("a template that is not a literal is a problem, named by class, method and line") {
            val problem = catalog.problems.single { it.contains("TextCatalogFixtures") }
            problem shouldStartWith "com.example.dpop.texts.TextCatalogFixtures.notALiteral:"
        }

        then("ids are the runtime's") {
            catalog.entries.first { it.template == "Innen" }.id shouldBe Text("Innen").id
        }
    }
})
