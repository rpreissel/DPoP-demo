package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.channel.DisclosingDemoDisclosure
import com.example.dpop.orchestrator.channel.WithheldDemoDisclosure
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaCall
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import io.kotest.core.spec.style.BehaviorSpec
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.example.dpop.orchestrator.kernel.AuthIntent

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

    given("the journey entity (AuthJourney) and its repository") {
        then("outside the journey package, only RunningJourney reaches a journey - and retention deletes old rows") {
            // docs/invarianten.md I-2 (review 2026-09 fahrplan Phase D step 18): a finished journey
            // takes no more tool results. JourneyService only accepts a RunningJourney, whose one
            // factory refuses a finished journey - but that holds only while nobody outside the
            // machine gets at the entity by another road.
            noClasses()
                .that().resideOutsideOfPackages(
                    "com.example.dpop.orchestrator.journey..",
                    "com.example.dpop.orchestrator.retention.."
                )
                .should().dependOnClassesThat().haveFullyQualifiedName("com.example.dpop.orchestrator.journey.AuthJourney")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("com.example.dpop.orchestrator.journey.AuthJourneyRepository")
                .because("the channel layer acts on journeys only through RunningJourney")
                .check(classes)
        }
    }

    given("the account a journey ran for (AuthJourney.accountId)") {
        then("no production code reads it - the account in hand is the channel's") {
            // Review 2026-09, fahrplan Phase D 21: a second readable copy of the same fact is how
            // `journey.accountId ?: channel.accountId` spread to eleven places and grew a dead branch.
            noClasses()
                .should().callMethod("com.example.dpop.orchestrator.journey.AuthJourney", "getAccountId")
                .because("AuthJourney.accountId is an audit record; decisions read ChannelSession.accountId")
                .check(classes)
        }
    }

    given("the versioned HTTP layer (api.v1)") {
        then("nothing outside it depends on it") {
            // api.v1 is how requests reach the orchestrator: routes, request bodies, parameter
            // binding, the OpenAPI description. The channel services, the access guards and the
            // response shapes used to live there too, so everything the controllers call was
            // "v1" - a second version could only have imported v1 or copied it. Now the arrow
            // points one way: api.v1 -> channel/journey/session, never back.
            noClasses()
                .that().resideOutsideOfPackage("com.example.dpop.orchestrator.api..")
                .should().dependOnClassesThat().resideInAPackage("com.example.dpop.orchestrator.api..")
                .because(
                    "api.v1 is the adapter; logic and response shapes live below it (channel, tool_api), " +
                        "so a new API version can sit next to v1 without importing it"
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

    given("the backend's console (review 2026-09, M-11)") {
        then("nothing prints to STDOUT/STDERR - codes and recipients must not end up in a container log") {
            noClasses()
                // The Keycloak migration runner is a separate build tool that reports its progress on
                // the console by design; it never sees a code or a recipient.
                .that().resideOutsideOfPackage("com.example.dpop.kcmigrate..")
                .should().dependOnClassesThat().haveFullyQualifiedName("kotlin.io.ConsoleKt")
                .orShould().accessField(System::class.java, "out")
                .orShould().accessField(System::class.java, "err")
                .because(
                    "the println calls this replaced put TANs and email codes together with phone numbers " +
                        "and addresses on STDOUT, regardless of demo mode; logging goes through SLF4J, " +
                        "and a simulated provider keeps what it sent in its own outbox"
                )
                .check(everything)
        }
    }

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

    given("outbound Keycloak Admin API calls") {
        then("no @Transactional class makes one - a DB transaction never spans a network round trip") {
            // The rule KeycloakAccountRemovalListener states for itself ("never hold the account's own
            // DB transaction open across a network call to Keycloak") and enforces by listening
            // AFTER_COMMIT. It was only ever a comment, and three places broke it: JourneyService's
            // logout transition, RetentionJob's dead-session probe, and KcTokenProvider. The first
            // two now publish an event / probe before the transaction; the third is a named,
            // deliberate exception below.
            //
            // Holding a transaction across a remote call keeps row locks for as long as the remote
            // service takes to answer - under load that turns one slow Keycloak into a stalled
            // orchestrator, and the failure looks like a database problem.
            noClasses()
                .that().areAnnotatedWith(Transactional::class.java)
                .and().resideOutsideOfPackage("com.example.dpop.orchestrator.kc..")
                // The one declared exception: minting an account token IS a Keycloak round trip
                // that has to happen while serving the request, and its transaction exists to write
                // the refresh-token cache back onto the AuthContext it just read. Bounded to one
                // call per token request (never per row), and it cannot be moved after the commit
                // because its result is the response. Named here so it stays the ONLY one.
                .and().doNotHaveFullyQualifiedName("com.example.dpop.orchestrator.session.KcTokenProvider")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("com.example.dpop.orchestrator.kc.KeycloakAdminClient")
                .because(
                    "a transaction that spans a Keycloak round trip holds row locks for the duration of a " +
                        "remote call; publish an event and act on it AFTER_COMMIT instead, the way " +
                        "KeycloakAccountRemovalListener and KeycloakSessionLogoutListener do"
                )
                .check(everything)
        }
    }

    given("the demo block of a response") {
        then("only DemoDisclosure builds one, so a deployment can switch disclosure off for good") {
            // What travels in `demo` is a plaintext TAN, the fixed demo password, and every seeded
            // persona's KVNR/name/address. Two services used to build a DemoInfo unconditionally,
            // so "never part of the production contract" was a doc comment with nothing behind it.
            // With construction confined to one property-gated bean, `demo.mode=false` removes
            // the values from every response rather than filtering them out of some.
            noClasses()
                .that().resideOutsideOfPackage("com.example.dpop.tool_api..")
                .and().doNotHaveFullyQualifiedName(DisclosingDemoDisclosure::class.java.name)
                .and().doNotHaveFullyQualifiedName(WithheldDemoDisclosure::class.java.name)
                .should().callConstructorWhere(
                    DescribedPredicate.describe<JavaCall<*>>("construct a DemoInfo") { call ->
                        call.target.owner.fullName == "com.example.dpop.tool_api.DemoInfo"
                    }
                )
                .because(
                    "DemoDisclosure is the single place that decides whether this deployment discloses " +
                        "demo-only values at all; a second construction site would silently reinstate the " +
                        "unconditional path"
                )
                .check(everything)
        }
    }

    given("the orchestrator's own packages") {
        then("they form a DAG - no package depends, directly or indirectly, on one that depends on it") {
            // Spring Modulith verifies boundaries BETWEEN top-level modules and never looks inside
            // one. The orchestrator is by far the largest module here, and nothing checked its
            // interior: session <-> policy, session <-> journey, session <-> journeytrace,
            // kc <-> dpop and session -> api.v1 had all grown into cycles.
            //
            // Almost every one was a NAME in the wrong package rather than a real entanglement -
            // AuthIntent, AmrSource, AcrLevels and OrchestratorException now live in `kernel`,
            // which depends on nothing; the journey trace takes values instead of the entities it
            // traces; retention, which spans sessions and journeys alike, sits above both instead
            // of inside one.
            //
            // A cycle is not a style question: it means the two packages can only be understood,
            // tested and changed together, and it is how a module quietly becomes one lump.
            slices()
                .matching("com.example.dpop.orchestrator.(*)..")
                .should().beFreeOfCycles()
                .check(everything)
        }
    }
})
