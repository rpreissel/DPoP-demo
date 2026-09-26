package com.example.dpop.orchestrator

import org.springframework.modulith.ApplicationModule

/**
 * The only module that may reference the others (docs/08-projektrahmen.md #3) - which is exactly
 * why its allowed set has to be written down: it is the one place where a new edge would not
 * stand out on its own.
 *
 * Where to start reading (docs/adr/ADR-040-fachkern-im-paket-domain.md): everything in `domain` is
 * the rules, free of any framework -
 * 1. `domain.AuthIntent` - the goals a user can have;
 * 2. `domain.journey.IntentStrategy` - how an intent decides its next step (`Transition`, `Action`);
 * 3. one pair of state and strategy, e.g. `domain.journey.state.RegisterState` with
 *    `domain.journey.strategy.RegisterStrategy`;
 * 4. `domain.policy.AuthPolicy` and `DefaultAuthPolicy` - which level the evidence is worth;
 * 5. `domain.journey.AccountRules` and `CredentialRules` - what the acting phase may do to accounts.
 * Everything outside `domain` (journey, channel, session, kc, api) is how those rules are run,
 * stored and served.
 *
 * Verified by `DpopApplicationTests.modulithStructureIsValid`. Kotlin has no package
 * annotations, but `@ApplicationModule` is `@Target({PACKAGE, TYPE})` and meta-annotated
 * `@PackageInfo`, so this otherwise-unused type serves as the package descriptor. A
 * `package-info.kt` carrying only `@file:ApplicationModule` does NOT work: a Kotlin file without
 * declarations compiles to no class at all, so the annotation is silently discarded and the
 * module has no enforced boundary.
 */
@ApplicationModule(
    allowedDependencies = ["tool_spi", "tool_api", "account", "kcmigrate", "demo_seed", "texts", "demo_mode"]
)
internal class ModuleMetadata
