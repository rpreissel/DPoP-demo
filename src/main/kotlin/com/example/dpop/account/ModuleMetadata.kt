package com.example.dpop.account

import org.springframework.modulith.ApplicationModule

/**
 * The account module and method modules are independent; they share only tool_spi/tool_api contracts.
 *
 * Inside (docs/adr/ADR-040-fachkern-im-paket-domain.md): `AccountService` and `AccountProfile` are
 * what other modules see; `domain` holds the rules (`AnchorDecision`, `normalizeClaimValue`,
 * `PassportForm`), `application` the services that apply them (`ClaimLedger`, `AnchorRegistry`,
 * `ChangeLog`, `IdentityMatchingService`), `infrastructure` the entities and repositories.
 * `AccountArchitectureTest` keeps the domain free of both.
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
@ApplicationModule(allowedDependencies = ["tool_spi", "tool_api", "texts"])
internal class ModuleMetadata
