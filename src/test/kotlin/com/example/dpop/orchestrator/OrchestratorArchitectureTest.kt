package com.example.dpop.orchestrator

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.core.spec.style.BehaviorSpec
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service

/**
 * Locks in dependency directions the codebase relies on but that nothing mechanically enforced -
 * exactly the kind of gap that let JourneyService import DeleteAccountStrategy directly (a
 * concrete IntentStrategy implementation) instead of staying generic over every intent via
 * `strategiesByIntent: Map<AuthIntent, IntentStrategy<*>>`. Spring Modulith's own
 * `ApplicationModules.verify()` (DpopApplicationTests) only checks boundaries BETWEEN top-level
 * modules (orchestrator, account, auth_sms, ...) - these rules check layering WITHIN orchestrator,
 * which Modulith never sees.
 */
class OrchestratorArchitectureTest : BehaviorSpec({

    // Test code has its own, deliberate exceptions to these rules (e.g. StrategyTestFixtures
    // constructs DefaultAuthPolicy directly - there is no Spring context in a pure unit test) -
    // these rules are about production layering, so test classes are excluded from the scan.
    val classes = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.example.dpop.orchestrator")

    given("the journey package's generic machine (JourneyService, IntentStrategy, Decision, JourneyState, ...)") {
        then("it never depends on one concrete IntentStrategy implementation") {
            noClasses()
                .that().resideInAnyPackage(
                    "com.example.dpop.orchestrator.journey",
                    "com.example.dpop.orchestrator.journey.state"
                )
                .should().dependOnClassesThat().resideInAPackage("com.example.dpop.orchestrator.journey.strategy..")
                .because(
                    "the machine is generic over every AuthIntent via IntentStrategy/strategiesByIntent - " +
                        "depending on one concrete strategy class breaks that (and was exactly today's bug: " +
                        "JourneyService referenced DeleteAccountStrategy.REQUIRED_ACR directly instead of a " +
                        "constant in the generic journey package)"
                )
                .check(classes)
        }
    }

    given("AuthPolicy's single implementation (DefaultAuthPolicy)") {
        then("nothing outside the policy package depends on it directly") {
            noClasses()
                .that().resideOutsideOfPackage("com.example.dpop.orchestrator.policy..")
                .should().dependOnClassesThat().haveFullyQualifiedName("com.example.dpop.orchestrator.policy.DefaultAuthPolicy")
                .because("every caller is meant to go through the AuthPolicy interface (Spring-injected), never the concrete implementation - the same reasoning as the journey/strategy rule above")
                .check(classes)
        }
    }

    given("IntentStrategy implementations (docs/04-orchestrierung.md #4, Decision: \"Die Strategie bekommt nie Services, nur einen lesenden JourneyContext ... Sie entscheidet, sie wirkt nicht.\")") {
        then("they never depend on a @Service or @Repository - only on the read-only JourneyContext handed to next()/interpret()") {
            val isServiceOrRepository = DescribedPredicate.describe<JavaClass>("annotated with @Service or @Repository") { clazz ->
                clazz.isAnnotatedWith(Service::class.java) || clazz.isAnnotatedWith(Repository::class.java)
            }
            noClasses()
                .that().implement("com.example.dpop.orchestrator.journey.IntentStrategy")
                .should().dependOnClassesThat(isServiceOrRepository)
                .because(
                    "a strategy DECIDES, it never ACTS (IntentStrategy's own class doc) - account creation, " +
                        "evidence recording, device linking all happen once in JourneyService instead, so no " +
                        "intent can forget them or duplicate them differently; a strategy that could inject a " +
                        "service could act directly and silently break that guarantee"
                )
                .check(classes)
        }
    }
})
