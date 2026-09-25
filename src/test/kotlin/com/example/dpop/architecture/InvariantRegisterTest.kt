package com.example.dpop.architecture

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Keeps docs/invarianten.md honest: every mechanism it names must exist, and every rule must name
 * one or be marked as a gap. A renamed test or a dropped constraint then fails here instead of
 * leaving the register claiming a protection that is gone.
 */
class InvariantRegisterTest : BehaviorSpec({

    val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("docs/invarianten.md")) }
    val register = root.resolve("docs/invarianten.md").readText()

    fun filesUnder(dir: String, vararg extensions: String): List<Path> =
        Files.walk(root.resolve(dir)).use { paths -> paths.filter { it.extension in extensions }.toList() }

    val testClasses = filesUnder("src/test", "kt", "java").map { it.name.substringBeforeLast('.') }.toSet()
    val mainClasses = filesUnder("src/main", "kt", "java").map { it.name.substringBeforeLast('.') }.toSet() +
        filesUnder("src/main/kotlin", "kt").flatMap { file ->
            Regex("""(?m)^(?:[a-z ]*)(?:class|interface|object|annotation class|data class|enum class) (\w+)""")
                .findAll(file.readText()).map { it.groupValues[1] }.toList()
        }
    val migrations = filesUnder("src/main/resources/db/migration", "sql").joinToString("\n") { it.readText() }

    /** One entry per "- **I-n ...**" bullet: its id and the lines belonging to it. */
    val entries: Map<String, String> = Regex("""(?m)^- \*\*(I-\d+) .*?(?=^- \*\*I-|\z)""", RegexOption.DOT_MATCHES_ALL)
        .findAll(register).associate { it.groupValues[1] to it.value }

    val mechanism = Regex("`(test|archunit|type|sql):([A-Za-z0-9_]+)`")

    given("docs/invarianten.md") {
        then("it lists invariants") {
            (if (entries.size < 10) listOf("only ${entries.size} entries found") else emptyList()).shouldBeEmpty()
        }

        then("every rule names a mechanism or is marked as a gap") {
            entries.filter { (_, text) -> mechanism.find(text) == null && "Lücke" !in text }.keys.shouldBeEmpty()
        }

        then("every named mechanism exists") {
            val missing = entries.flatMap { (id, text) ->
                mechanism.findAll(text).mapNotNull { match ->
                    val (kind, name) = match.destructured
                    val exists = when (kind) {
                        "test", "archunit" -> name in testClasses
                        "type" -> name in mainClasses
                        "sql" -> Regex("""\b$name\b""").containsMatchIn(migrations)
                        else -> false
                    }
                    if (exists) null else "$id: $kind:$name"
                }.toList()
            }
            missing.shouldBeEmpty()
        }
    }
})
