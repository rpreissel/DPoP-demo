package com.example.dpop.texts

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.util.Properties

/**
 * Every language of every bundle - German included, it is reworded like any other - carries a
 * wording for each template in the code, nothing left over, and the same placeholders as the
 * template (docs/adr/ADR-033).
 *
 * A missing or left-over wording is a warning while developing: the client shows the template
 * meanwhile (`TextRef.template`), so a new text works at once. With `-PstrictTexts` - for a build
 * that runs outside the demo mode - it fails; `/translate-texts <lang>` makes it green. A wrong
 * placeholder and an id two templates share always fail.
 */
class TextTranslationsTest : BehaviorSpec({

    val catalog = TextCatalog.all
    val strict = System.getProperty("texts.strict").toBoolean()

    /** Fails with -PstrictTexts, otherwise names what `/translate-texts` would fix. */
    fun List<String>.requireWorded() {
        if (strict) shouldBeEmpty() else forEach { System.err.println("WARN text: $it") }
    }

    given("the ids of all templates") {
        then("no two different templates share one - the slug is cut short, the hash tells them apart") {
            catalog.byBundle.values.flatMap { it.values }
                .groupBy { it.id }
                .filterValues { entries -> entries.map { it.template }.distinct().size > 1 }
                .map { (id, entries) -> "$id: ${entries.map { it.template }.distinct()}" }
                .shouldBeEmpty()
        }
    }

    fun keycloakWordings(language: String): Map<String, String> {
        val file = java.nio.file.Path.of("keycloak-extension/src/main/resources/theme/orchestrator/login/messages/messages_$language.properties")
        if (!java.nio.file.Files.exists(file)) return emptyMap()
        val properties = Properties().apply { java.nio.file.Files.newBufferedReader(file, Charsets.UTF_8).use { load(it) } }
        return properties.stringPropertyNames().associateWith { properties.getProperty(it) }
    }

    fun wordings(bundle: String, language: String): Map<String, String> {
        val resource = TextBundle::class.java.classLoader.getResource("texts/$bundle/texts_$language.properties") ?: return emptyMap()
        val properties = Properties().apply { resource.openStream().reader(Charsets.UTF_8).use { load(it) } }
        return properties.stringPropertyNames().associateWith { properties.getProperty(it) }
    }

    given("the same template in several bundles - a tool name in the app and on the login page") {
        then("reads the same in each, per language") {
            SUPPORTED_LANGUAGES.flatMap { language ->
                val byBundle = catalog.byBundle.keys.associateWith { wordings(it, language) } +
                    ("keycloak" to keycloakWordings(language))
                byBundle.values.flatMap { it.keys }.distinct().mapNotNull { id ->
                    val variants = byBundle.mapNotNull { (bundle, w) -> w[id]?.let { bundle to it } }
                    if (variants.map { it.second }.distinct().size > 1) "$language $id: $variants - run /translate-texts $language" else null
                }
            }.shouldBeEmpty()
        }
    }

    catalog.byBundle.forEach { (bundle, entries) ->
        SUPPORTED_LANGUAGES.forEach { language ->
            given("bundle $bundle, language $language") {
                val wordings = wordings(bundle, language)

                then("every template has a wording") {
                    entries.values.filter { it.id !in wordings }
                        .map { "${it.id} \"${it.template}\" (${it.locations.first()}) - run /translate-texts $language" }
                        .requireWorded()
                }

                then("no wording is left over") {
                    (wordings.keys - entries.keys).map { "${it}=${wordings[it]} - run /translate-texts $language" }.requireWorded()
                }

                then("each wording keeps the template's placeholders") {
                    entries.values.filter { it.id in wordings }
                        .filter { Text.placeholdersOf(wordings.getValue(it.id)) != Text.placeholdersOf(it.template) }
                        .map { "${it.id}: \"${it.template}\" vs \"${wordings[it.id]}\"" }
                        .shouldBeEmpty()
                }
            }
        }
    }
})
