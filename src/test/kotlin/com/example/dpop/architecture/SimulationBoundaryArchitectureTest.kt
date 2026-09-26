package com.example.dpop.architecture

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * The line between the production-ready core and the simulated foreign systems (ADR-35).
 *
 * A simulation stands in for a real system, and the core may only rely on what that system would
 * promise. So the core reaches a simulation only along an edge named here - each with its reason -
 * and otherwise through a port in `tool_api` (`PersonDirectory`, `PersonMasterData`, ...). Spring
 * Modulith checks allowed dependencies per module, but the orchestrator declares none; this is the
 * rule that says which of ITS classes may see a simulation at all.
 */
class SimulationBoundaryArchitectureTest : BehaviorSpec({

    val everything = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.example.dpop")

    val simulations = listOf(
        "com.example.dpop.ext_personenverzeichnis",
        "com.example.dpop.kobil_mock",
        "com.example.dpop.nect_mock",
        "com.example.dpop.sms_mock",
        "com.example.dpop.mail_mock",
        "com.example.dpop.demo_seed",
    )

    /** Named edges from the core into a simulation: source package (or class) -> simulation, with the reason. */
    data class Edge(val from: String, val to: String, val why: String)
    val edges = listOf(
        // A procedure talks to its foreign system directly, not through a second abstraction
        // (docs/08-projektrahmen.md M-3) - except the person register, which is reached over ports
        // only (PersonDirectory, ActivationCodes, DemoPersonDirectory; ADR-31 addendum).
        Edge("com.example.dpop.auth_kobil", "com.example.dpop.kobil_mock", "KOBIL is the auth_kobil procedure's foreign system"),
        Edge("com.example.dpop.id_nect", "com.example.dpop.nect_mock", "Nect is the id_nect procedure's foreign system"),
        Edge("com.example.dpop.auth_sms", "com.example.dpop.sms_mock", "the SMS provider (review 2026-09, M-11)"),
        Edge("com.example.dpop.auth_email", "com.example.dpop.mail_mock", "the mail server (review 2026-09, M-11)"),
        // Demo surfaces of the orchestrator - Vorführrahmen, hardened later (ADR-35).
        Edge("com.example.dpop.orchestrator.admin.AdminAccountsController", "com.example.dpop.demo_seed", "demo reset seeds the demo accounts again"),
    )

    fun simulationOf(target: JavaClass): String? =
        simulations.firstOrNull { target.packageName == it || target.packageName.startsWith("$it.") }

    fun allowed(source: JavaClass, simulation: String): Boolean =
        simulationOf(source) != null ||
            edges.any { edge ->
                edge.to == simulation &&
                    (source.name == edge.from || source.name.startsWith("${edge.from}$") ||
                        source.packageName == edge.from || source.packageName.startsWith("${edge.from}."))
            }

    val reachSimulationsOnlyAlongNamedEdges = object : ArchCondition<JavaClass>("reach a simulation only along a named edge") {
        override fun check(source: JavaClass, events: ConditionEvents) {
            source.directDependenciesFromSelf
                .mapNotNull { dependency -> simulationOf(dependency.targetClass)?.let { it to dependency } }
                .filterNot { (simulation, _) -> allowed(source, simulation) }
                .forEach { (_, dependency) -> events.add(SimpleConditionEvent.violated(source, dependency.description)) }
        }
    }

    given("the core (everything that is not itself a simulation)") {
        then("it reaches a simulated foreign system only along a named edge, otherwise through a port") {
            classes()
                .that().resideInAPackage("com.example.dpop..")
                .should(reachSimulationsOnlyAlongNamedEdges)
                .because("the core may rely only on what the real system would promise - its port (ADR-35)")
                .check(everything)
        }
    }

    given("the named edges") {
        then("each is still used - a stale edge would silently widen the boundary") {
            fun isSource(javaClass: JavaClass, edge: Edge) =
                javaClass.name == edge.from || javaClass.name.startsWith("${edge.from}$") ||
                    javaClass.packageName == edge.from || javaClass.packageName.startsWith("${edge.from}.")
            edges.filterNot { edge ->
                everything.any { source ->
                    isSource(source, edge) && source.directDependenciesFromSelf.any { simulationOf(it.targetClass) == edge.to }
                }
            }.shouldBeEmpty()
        }
    }
})
