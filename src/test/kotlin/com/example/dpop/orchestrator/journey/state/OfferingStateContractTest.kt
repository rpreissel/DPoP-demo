package com.example.dpop.orchestrator.journey.state

import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.ToolId
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.primaryConstructor

/**
 * [OfferingState.declining] is implemented identically in every offering state - twenty copies of
 * `copy(declined = declined + toolId, active = null)`. That duplication is not avoidable in
 * Kotlin (a `data class` cannot inherit `copy`, and a field of an unknown one cannot be changed
 * generically), but it is not harmless either: it encodes a rule, and an implementation that
 * forgot `active = null` would leave the just-declined tool running while the journey believes it
 * was put aside.
 *
 * So the rule is asserted here instead, against EVERY implementation - discovered and constructed
 * reflectively rather than listed, so a twenty-first state is covered the day it is written rather
 * than the day someone remembers to add it here.
 */
class OfferingStateContractTest : BehaviorSpec({

    /**
     * A value for a constructor parameter the state needs but this contract does not care about -
     * EXCEPT [ToolRef], which it very much cares about: `active` is an optional parameter, so
     * leaving it out would build a sample that already has `active == null` and make the
     * "clears the active tool" assertion below pass no matter what `declining` does. (It did, until
     * a mutation showed the test staying green against a deliberately broken implementation.)
     */
    fun sampleFor(parameter: KParameter): Any? = when (parameter.type.classifier) {
        ToolRef::class -> ToolRef(ToolId("probe-a"), UUID.randomUUID(), "input")
        List::class -> listOf(ToolId("probe-a"), ToolId("probe-b"))
        Set::class -> emptySet<ToolId>()
        AcrLevel::class -> AcrLevel.LOA1
        Boolean::class -> false
        String::class -> "probe"
        Long::class -> 1L
        UUID::class -> UUID.randomUUID()
        else -> null
    }

    fun instantiate(type: KClass<*>): OfferingState? {
        val constructor = type.primaryConstructor ?: return null
        val arguments = constructor.parameters.mapNotNull { parameter ->
            val value = sampleFor(parameter)
            when {
                value != null -> parameter to value
                // Anything else we have no sample for must be optional, or this state cannot be
                // exercised here - surfaced as a failure below rather than silently skipped.
                parameter.isOptional -> null
                else -> return null
            }
        }.toMap()
        return constructor.callBy(arguments) as? OfferingState
    }

    val implementations = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.example.dpop.orchestrator.journey.state")
        .filter { it.isAssignableTo(OfferingState::class.java) && !it.isInterface }
        .map { Class.forName(it.name).kotlin }

    given("every state that offers tools") {
        then("all of them can be exercised here - none is silently skipped") {
            implementations.shouldNotBeEmpty()
            val unconstructible = implementations.filter { instantiate(it) == null }
            withClue("no sample value for a required constructor parameter - add one to sampleFor()") {
                unconstructible.map { it.simpleName } shouldBe emptyList()
            }
        }

        then("declining a tool records it AND stops the tool that was running") {
            implementations.forEach { type ->
                val state = instantiate(type)!!
                withClue("${type.simpleName}: the sample must HAVE a running tool, else the assertion below proves nothing") {
                    state.active.shouldNotBeNull()
                }
                val declined = state.declining(ToolId("probe-a"))

                withClue("${type.simpleName} must record the declined tool") {
                    declined.declined shouldBe state.declined + ToolId("probe-a")
                }
                // The half that is easy to forget: a decline that leaves `active` set would keep
                // the abandoned tool addressable while the journey has already moved past it.
                withClue("${type.simpleName} must clear the active tool when one is declined") {
                    declined.active.shouldBeNull()
                }
                withClue("${type.simpleName} must not change what it offers") {
                    declined.offered shouldBe state.offered
                }
            }
        }
    }
})
