package com.example.dpop.account

import org.springframework.modulith.ApplicationModule

/**
 * The account module must never depend on a method module. That is what keeps auth_email -> account (see that module) acyclic and therefore permissible at all.
 *
 * `tool_api` is safe alongside `tool_spi`: it is the shared SPI, not a method module, and does
 * not depend back on `account` - `AccountService` implements `tool_api.AccountDirectory` directly
 * (docs/04-orchestrierung.md #5), so a tool controller never needs to depend on `account` itself.
 *
 * Verified by `DpopApplicationTests.modulithStructureIsValid`. Kotlin has no package
 * annotations, but `@ApplicationModule` is `@Target({PACKAGE, TYPE})` and meta-annotated
 * `@PackageInfo`, so this otherwise-unused type serves as the package descriptor - no
 * `package-info.java` and no Java source set needed.
 */
@ApplicationModule(allowedDependencies = ["tool_spi", "tool_api"])
internal class ModuleMetadata
