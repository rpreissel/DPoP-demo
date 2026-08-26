package com.example.dpop.orchestrator

import org.springframework.modulith.ApplicationModule

/**
 * The only module that may reference the others (docs/08-projektrahmen.md #3) - which is exactly
 * why its allowed set has to be written down: it is the one place where a new edge would not
 * stand out on its own.
 *
 * Verified by `DpopApplicationTests.modulithStructureIsValid`. Kotlin has no package
 * annotations, but `@ApplicationModule` is `@Target({PACKAGE, TYPE})` and meta-annotated
 * `@PackageInfo`, so this otherwise-unused type serves as the package descriptor. A
 * `package-info.kt` carrying only `@file:ApplicationModule` does NOT work: a Kotlin file without
 * declarations compiles to no class at all, so the annotation is silently discarded - which is
 * how this module went without an enforced boundary until now.
 */
@ApplicationModule(
    allowedDependencies = [
        "tool_spi", "tool_api", "account", "ext_stammdaten",
        "auth_password", "auth_email"
    ]
)
internal class ModuleMetadata
