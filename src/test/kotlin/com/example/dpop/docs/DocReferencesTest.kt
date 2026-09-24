package com.example.dpop.docs

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.io.File

/**
 * Every pointer from code into the documentation - `docs/<datei>.md`, optionally with a numbered
 * section (`#3`, `Abschnitt 3`) - must lead somewhere: the file exists, and a numbered section
 * exists as a heading there. Docs get reworded and archived; without this check a comment keeps
 * pointing at a file that is gone, and nobody notices.
 *
 * Comments and strings wrap long paths across lines (`docs/ideen/web-keycloak-` / `kanal.md`), so
 * the scan joins such breaks before matching.
 */
class DocReferencesTest : BehaviorSpec({

    val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    val sourceRoots = listOf(
        "src/main", "src/test", "keycloak-extension/src", "keycloak-migrations/src", "frontend/src", "frontend/scripts"
    )
    val extensions = setOf("kt", "kts", "java", "sql", "yml", "ftl", "ts", "tsx", "mjs", "md")

    // A word broken after a hyphen, or a path broken after a slash, continued on the next line
    // behind a comment or string-concatenation prefix.
    val hyphenBreak = Regex("""-\s*"?\s*\+?\s*\n\s*(?:\*|//|--|#|"|\+)*\s*""")
    val slashBreak = Regex("""/\s*\n\s*(?:\*|//|--|#)*\s*""")
    val reference = Regex("""docs/([0-9A-Za-z/_.-]+?\.md)(?:\s*(?:#|Abschnitt |section |§)\s*([0-9]+[a-z]?))?""")
    val numberedHeading = Regex("""^#{2,4}\s+([0-9]+[a-z]?)[).]""", RegexOption.MULTILINE)

    val headingsByDoc = mutableMapOf<File, Set<String>>()
    fun numberedHeadingsOf(doc: File) = headingsByDoc.getOrPut(doc) {
        numberedHeading.findAll(doc.readText()).map { it.groupValues[1] }.toSet()
    }

    given("the code base") {
        then("every documentation reference points to an existing file and section") {
            val broken = sourceRoots.map { File(root, it) }.filter { it.isDirectory }
                .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension in extensions && "generated" !in it.path }.toList() }
                .flatMap { file ->
                    val text = file.readText().replace(hyphenBreak, "-").replace(slashBreak, "/")
                    reference.findAll(text).mapNotNull { match ->
                        val doc = File(root, "docs/" + match.groupValues[1])
                        val section = match.groupValues[2]
                        when {
                            !doc.exists() -> "${file.relativeTo(root)}: ${match.value} - Datei fehlt"
                            section.isNotEmpty() && section !in numberedHeadingsOf(doc) ->
                                "${file.relativeTo(root)}: ${match.value} - Abschnitt fehlt"
                            else -> null
                        }
                    }.toList()
                }
            broken.shouldBeEmpty()
        }
    }
})
