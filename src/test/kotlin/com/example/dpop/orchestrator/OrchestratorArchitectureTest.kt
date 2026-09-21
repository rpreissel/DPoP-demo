package com.example.dpop.orchestrator

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaCall
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

    given("the acting phase (JourneyActionExecutor, docs/04-orchestrierung.md #4, \"Die vier Phasen eines Uebergangs\")") {
        then("it never depends on the driving phase (JourneyService) or on a concrete IntentStrategy") {
            noClasses()
                .that().haveFullyQualifiedName("com.example.dpop.orchestrator.journey.JourneyActionExecutor")
                .should().dependOnClassesThat().haveFullyQualifiedName("com.example.dpop.orchestrator.journey.JourneyService")
                .orShould().dependOnClassesThat().resideInAPackage("com.example.dpop.orchestrator.journey.strategy..")
                .because(
                    "the executor WRITES and returns - it never advances a journey, never routes and never starts a " +
                        "sub-journey; that one-way dependency is what keeps the recursion in JourneyService." +
                        "applyTransition the only recursion the machine has"
                )
                .check(classes)
        }
    }

    given("the routing phase (JourneyRouting)") {
        then("it stays a pure function of (state, availableTools) - no repository, no journey writing") {
            noClasses()
                .that().haveFullyQualifiedName("com.example.dpop.orchestrator.journey.JourneyRouting")
                .should().dependOnClassesThat().haveFullyQualifiedName("com.example.dpop.orchestrator.journey.AuthJourneyRepository")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("com.example.dpop.orchestrator.journey.AuthJourney")
                .because(
                    "\"next is a pure function of the state\" (docs/04-orchestrierung.md #4) is only checkable by " +
                        "reading one small class as long as that class cannot reach the journey itself"
                )
                .check(classes)
        }
    }

    // The account-takeover class of bug: a session ending up bound to an account it never proved
    // it owns. It arises whenever the SAFETY CHECK lives per Action-handler while the CHOICE of
    // which handler runs is a strategy's to make. The rules below make that structural instead of
    // reviewed: identity resolution, account absorption and device linking are reachable from
    // exactly one class, so no strategy - and no future combination of tools or reordering of
    // them - can reach a takeover path that skips the gate.
    val gate = "com.example.dpop.orchestrator.journey.JourneyActionExecutor"

    // Scanned across the WHOLE application, not just `orchestrator` like the layering rules above:
    // the point of these three is that nobody anywhere reaches a takeover path, and a tool module
    // (auth_email, id_eid, ...) injecting IdentityResolver for itself is exactly the case the
    // narrow scope would have missed.
    val everything = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.example.dpop")

    given("identity resolution (IdentityResolver - \"does this attested identity belong to an existing account?\")") {
        then("only the acting phase can ask, so the answer can never be acted on without its gate") {
            noClasses()
                .that().doNotHaveFullyQualifiedName(gate)
                // The port's own declaration site and its single implementation must know it;
                // named individually rather than exempting all of tool_api/account, so a SECOND
                // class in either package cannot quietly inherit the exemption.
                .and().doNotHaveFullyQualifiedName("com.example.dpop.tool_api.IdentityResolver")
                .and().doNotHaveFullyQualifiedName("com.example.dpop.account.internal.IdentityMatchingService")
                .should().dependOnClassesThat().haveFullyQualifiedName("com.example.dpop.tool_api.IdentityResolver")
                .because(
                    "resolving claims to an existing account is the first half of a takeover; the second half " +
                        "(actually binding the session to it) is gated in JourneyActionExecutor.accountOf. A " +
                        "strategy that could resolve for itself could act on the answer before that gate - " +
                        "exactly the shape of both account-takeover bugs found in this codebase"
                )
                .check(everything)
        }
    }

    // A tool session outlives its activation by real time (a TAN being typed, an eID redirect)
    // and carries resolved snapshots while it waits - the account a lookup resolved, the
    // enrollment an auth tool was pointed at. What keeps those from being used against a journey
    // that has since moved on is no longer a rule here but the TYPE SYSTEM: applyOutcome/abandon
    // take an AuthorizedToolContext, obtainable only from beginActivation (which creates the
    // session) or loadCurrent (which verifies it against the journey's active ToolRef). A write
    // path that skips the check does not compile, so there is nothing left for an ArchUnit rule
    // to catch - see tool_api/ToolEndpoint.kt.

    given("absorbing one account into another and linking a device to an account") {
        then("both happen in the acting phase only - never from a strategy, a controller or a tool") {
            val crossesAccounts = DescribedPredicate.describe<JavaCall<*>>(
                "absorb an account into another, or link a device to an account"
            ) { call ->
                val target = call.target
                (target.owner.fullName == "com.example.dpop.account.AccountService" &&
                    target.name == "absorbProvisionalAccount") ||
                    (target.owner.fullName == "com.example.dpop.orchestrator.session.SessionManagementService" &&
                        target.name == "linkDeviceToAccount")
            }
            noClasses()
                .that().doNotHaveFullyQualifiedName(gate)
                .should().callMethodWhere(crossesAccounts)
                .because(
                    "these two are the only writes that move a session/device onto an account it did not " +
                        "already hold. Keeping them in one class is what makes \"a takeover always passes " +
                        "accountOf\" checkable by reading one file instead of trusting every caller"
                )
                .check(everything)
        }
    }

    given("the DeviceAccountLink table itself") {
        then("only the service that owns the rebind rule may write it - not the repository directly") {
            // Closes the way AROUND the rule above: it watches calls to linkDeviceToAccount, but
            // DeviceAccountLinkRepository sits in an ordinary package, so injecting it and calling
            // save(DeviceAccountLink(key, accountId)) would bind a device with no rebind check and
            // no revocation of the previous account's device credentials - and the call-based rule
            // would not see it, because the service was never called.
            noClasses()
                .that().doNotHaveFullyQualifiedName("com.example.dpop.orchestrator.session.SessionManagementService")
                .and().doNotHaveFullyQualifiedName("com.example.dpop.orchestrator.session.AccountDeletionService")
                .and().doNotHaveFullyQualifiedName("com.example.dpop.orchestrator.session.DeviceAccountLinkRepository")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("com.example.dpop.orchestrator.session.DeviceAccountLinkRepository")
                .because(
                    "SessionManagementService.linkDeviceToAccount is where the 1:1 device->account invariant " +
                        "lives (docs/09-dpop.md #3) and AccountDeletionService is the one legitimate bulk " +
                        "remover; anything else reaching the table directly would bypass both"
                )
                .check(everything)
        }
    }
})
