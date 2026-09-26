package com.example.dpop.orchestrator.domain.journey

import com.example.dpop.orchestrator.domain.AuthIntent
import com.example.dpop.orchestrator.domain.journey.state.ConfirmPeerLoginState
import com.example.dpop.orchestrator.domain.journey.state.DeleteAccountState
import com.example.dpop.orchestrator.domain.journey.state.FastAccessState
import com.example.dpop.orchestrator.domain.journey.state.JourneyState
import com.example.dpop.orchestrator.domain.journey.state.KcSelectMethodState
import com.example.dpop.orchestrator.domain.journey.state.LogoutState
import com.example.dpop.orchestrator.domain.journey.state.LookupLoginState
import com.example.dpop.orchestrator.domain.journey.state.ManageAuthMethodsState
import com.example.dpop.orchestrator.domain.journey.state.ReIdentifyState
import com.example.dpop.orchestrator.domain.journey.state.RegisterEnrollFirstState
import com.example.dpop.orchestrator.domain.journey.state.RegisterState
import com.example.dpop.orchestrator.domain.journey.state.StepUpState
import com.example.dpop.orchestrator.journey.JourneyStateCodec
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.reflect.KClass

/**
 * The state diagrams in `docs/journeys/<intent>.md` are written by hand - they carry the conditions
 * in words ("Nachweis reicht für das geforderte Niveau"), which no generator can derive from the code.
 * What this test holds them to is the part that CAN drift silently: the states (ADR-40).
 *
 * - Every node a diagram names is a state of that intent, a sub-journey (an `AuthIntent`), or a node
 *   the diagram declares itself (`state "…" as X`) - so a renamed or removed state cannot linger.
 * - Every state of an intent appears in its diagram - so a new state cannot go undocumented.
 *
 * The arrows are not compared: whether a transition happens depends on a context that each executed
 * action changes (accounts created, evidence recorded), and reproducing that here would mean writing
 * the executor a second time. The strategy tests cover the transitions.
 */
class JourneyDiagramsTest : BehaviorSpec({

    val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.exists(it.resolve("docs/journeys")) }

    /** The nodes of every state diagram in [doc]: those that are drawn, and those the diagram declares itself. */
    fun diagramNodes(doc: String): Pair<Set<String>, Set<String>> {
        val blocks = Regex("```mermaid\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(root.resolve("docs/journeys/$doc.md").readText())
            .map { it.groupValues[1] }.filter { "stateDiagram" in it }.toList()
        val drawn = blocks.flatMap { block ->
            Regex("(?m)^\\s*([\\w\\[\\]*]+)\\s*-->\\s*([\\w\\[\\]*]+)").findAll(block).flatMap { sequenceOf(it.groupValues[1], it.groupValues[2]) }
        }.filter { it != "[*]" }.toSet()
        val declared = blocks.flatMap { block ->
            Regex("(?m)^\\s*state \"[^\"]*\" as (\\w+)").findAll(block).map { it.groupValues[1] }
        }.toSet()
        return drawn to declared
    }

    given("the hand-written journey diagrams") {
        DIAGRAMS.groupBy { it.doc }.forEach { (doc, entries) ->
            val (drawn, declared) = diagramNodes(doc)
            val states = entries.flatMap { entry -> JourneyStateCodec.concreteStates(entry.root).map { it.simpleName!! } }.toSet()

            then("$doc.md names only states that exist") {
                val unknown = drawn - states - declared - AuthIntent.entries.map { it.name }.toSet() - END_NODES
                withClue("Unbekannte Knoten - umbenannt, entfernt, oder als `state \"…\" as X` zu deklarieren") { unknown.shouldBeEmpty() }
            }

            then("$doc.md shows every state its intent has") {
                withClue("Zustände, die im Code existieren, aber im Diagramm fehlen") { (states - drawn).shouldBeEmpty() }
            }
        }
    }
}) {
    /** Which sealed root is documented in which file. */
    class Diagram(val root: KClass<out JourneyState>, val doc: String)

    companion object {
        /** `Finished` is drawn but never stored: a journey ends by a transition (docs/04-orchestrierung.md #3). */
        val END_NODES = setOf("Finished")

        val DIAGRAMS = listOf(
            Diagram(FastAccessState::class, "fast-access"),
            Diagram(RegisterState::class, "register"),
            Diagram(RegisterEnrollFirstState::class, "register"),
            Diagram(LookupLoginState::class, "lookup-login"),
            Diagram(KcSelectMethodState::class, "kc-select-method"),
            Diagram(StepUpState::class, "step-up"),
            Diagram(ManageAuthMethodsState::class, "manage-auth-methods"),
            Diagram(ConfirmPeerLoginState::class, "confirm-peer-login"),
            Diagram(DeleteAccountState::class, "delete-account"),
            Diagram(LogoutState::class, "logout"),
            Diagram(ReIdentifyState::class, "re-identify"),
        )
    }
}
