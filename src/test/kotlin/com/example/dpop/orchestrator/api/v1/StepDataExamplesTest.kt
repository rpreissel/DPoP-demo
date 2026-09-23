package com.example.dpop.orchestrator.api.v1

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path

/**
 * Jedes `stepData` in den Beispielen des Vertrags muss eine Form sein, die der Vertrag selbst
 * deklariert.
 *
 * Die Beispiele stehen als JSON-Text in `@ApiResponse(examples = ...)` und werden von keinem
 * Compiler geprueft. Nach der Typisierung von stepData zeigten zwoelf davon noch die alte Form ohne
 * Diskriminator - wer sie als Vorlage nahm, baute einen Client, der die echte Antwort nicht
 * erkennt. Geprueft wird deshalb gegen die Spec: `kind` muss im Mapping stehen, jedes Feld muss in
 * der zugehoerigen Form vorkommen, und jedes Pflichtfeld muss da sein.
 */
class StepDataExamplesTest : BehaviorSpec({

    given("the checked-in contract") {
        @Suppress("UNCHECKED_CAST")
        val spec = Yaml().load<Map<String, Any?>>(Files.readString(SNAPSHOT))
        @Suppress("UNCHECKED_CAST")
        val schemas = (spec["components"] as Map<String, Any?>)["schemas"] as Map<String, Map<String, Any?>>

        @Suppress("UNCHECKED_CAST")
        val stepDataSchema = schemas.getValue("StepData")
        @Suppress("UNCHECKED_CAST")
        val discriminator = stepDataSchema["discriminator"] as Map<String, Any?>
        val property = discriminator["propertyName"] as String
        @Suppress("UNCHECKED_CAST")
        val mapping = discriminator["mapping"] as Map<String, String>

        then("every stepData example names a declared shape and fits it") {
            val examples = mutableListOf<Map<*, *>>()
            collectStepData(spec, examples)
            examples.shouldNotBeEmpty()

            val problems = examples.flatMap { example ->
                val kind = example[property] as? String
                    ?: return@flatMap listOf("stepData ohne '$property': $example")
                val ref = mapping[kind]
                    ?: return@flatMap listOf("'$property: $kind' steht nicht im Mapping: $example")
                val shape = schemas.getValue(ref.substringAfterLast('/'))
                @Suppress("UNCHECKED_CAST")
                val declared = (shape["properties"] as? Map<String, Any?>).orEmpty().keys + property
                @Suppress("UNCHECKED_CAST")
                val required = (shape["required"] as? List<String>).orEmpty()

                (example.keys.map { it as String } - declared).map { "'$it' gibt es in $kind nicht: $example" } +
                    (required - example.keys.map { it as String }.toSet()).map { "Pflichtfeld '$it' fehlt in $kind: $example" }
            }
            problems.shouldBeEmpty()
        }
    }
}) {
    private companion object {
        private val SNAPSHOT: Path = Path.of("api", "openapi.yaml").toAbsolutePath()

        /** Walks the whole document; only `example`/`examples` subtrees carry response bodies. */
        private fun collectStepData(node: Any?, into: MutableList<Map<*, *>>, inExample: Boolean = false) {
            when (node) {
                is Map<*, *> -> node.forEach { (key, value) ->
                    val nowInExample = inExample || key == "example" || key == "examples"
                    if (nowInExample && key == "stepData" && value is Map<*, *>) into += value
                    collectStepData(value, into, nowInExample)
                }
                is List<*> -> node.forEach { collectStepData(it, into, inExample) }
            }
        }
    }
}
