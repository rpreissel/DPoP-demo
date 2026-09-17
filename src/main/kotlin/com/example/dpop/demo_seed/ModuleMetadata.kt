package com.example.dpop.demo_seed

import org.springframework.modulith.ApplicationModule

/**
 * Demo-only bootstrap: gives the `keycloak` profile's seeded test persons a real orchestrator
 * account on first boot, so the native-password/orchestrator-step-up flow has something real to
 * demonstrate. Its own module (not tucked into `auth_email`) because there never was a real
 * boundary reason to hide it there: nothing in Spring Modulith or
 * `DpopApplicationTests.modulithStructureIsValid` restricts calling a public method on a module
 * one is allowed to depend on. The live enrollment path consolidates claims generically itself
 * (`Action.AdoptCredential` -> `AccountService.recordClaims`); this module is simply a second,
 * equally direct caller of the same path (`AccountService.recordClaim`,
 * `ClaimSource.DEMO_BOOTSTRAP` naming its provenance) for a different reason - bootstrap seeding
 * instead of a live enrollment/identification run.
 *
 * `account` for `recordClaim`/`addAuthenticationMethod`/`createUnidentifiedAccount`/`resolveByAnchor`/`anchorValue`;
 * `tool_api` for `PersonDirectory` (resolves the seeded persons) and `PasswordCredentialPort`
 * (sets the demo password without depending on `auth_password` directly).
 *
 * Verified by `DpopApplicationTests.modulithStructureIsValid`. Kotlin has no package
 * annotations, but `@ApplicationModule` is `@Target({PACKAGE, TYPE})` and meta-annotated
 * `@PackageInfo`, so this otherwise-unused type serves as the package descriptor - no
 * `package-info.java` and no Java source set needed.
 */
@ApplicationModule(allowedDependencies = ["tool_spi", "tool_api", "account"])
internal class ModuleMetadata
