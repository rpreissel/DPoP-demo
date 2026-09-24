package com.example.dpop.texts

import java.nio.file.Files
import java.nio.file.Path

/**
 * `./gradlew exportTexts`: writes the collected source wordings per bundle to
 * `<dir>/<bundle>/texts_source.properties` - the input `/translate-texts` rewords from. Never
 * shipped; the bundles clients get are the per-language files under `src/main/resources/texts/`.
 */
fun main(args: Array<String>) {
    val dir = Path.of(args.single())
    val catalog = TextCatalog.all
    check(catalog.problems.isEmpty()) { catalog.problems.joinToString("\n") }
    catalog.byBundle.forEach { (bundle, entries) ->
        val file = dir.resolve(bundle).resolve("texts_source.properties")
        Files.createDirectories(file.parent)
        val lines = entries.values.sortedBy { it.id }.flatMap { entry ->
            entry.locations.map { "# $it" } + "${entry.id}=${escape(entry.template)}"
        }
        Files.writeString(file, (listOf("# Quelle: Vorlagen aus dem Code, Bundle $bundle - generiert, nicht bearbeiten.") + lines).joinToString("\n", postfix = "\n"))
        println("${entries.size} Texte -> $file")
    }
}

/** java.util.Properties escaping for a value read back through a UTF-8 Reader. */
fun escape(value: String): String =
    value.replace("\\", "\\\\").replace("\n", "\\n").let { if (it.startsWith(" ")) "\\$it" else it }
