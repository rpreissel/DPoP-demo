package com.example.dpop.demo_seed

import org.springframework.modulith.ApplicationModule

/**
 * Demo-only bootstrap: gives the `keycloak` profile's seeded test persons a real orchestrator
 * account on first boot, so the native-password/orchestrator-step-up flow has something real to
 * demonstrate. Its own module (not tucked into `auth_email`) because there never was a real
 * boundary reason to hide it there - see `AccountService.confirmEmail`'s own doc: nothing in
 * Spring Modulith or `DpopApplicationTests.modulithStructureIsValid` restricts calling a public
 * method on a module one is allowed to depend on. `auth_email`'s claim of being "the one module
 * that may call confirmEmail" was never build-checked, and `JourneyService` already calls it too
 * (generic `Action.AdoptCredential` handling) - this module is simply a second, equally direct
 * caller for a different reason (bootstrap seeding instead of a live enrollment).
 *
 * `account` for `confirmEmail`/`addAuthenticationMethod`/`findOrCreateAccount`; `tool_api` for
 * `PersonDirectory` (resolves the seeded persons) and `PasswordCredentialPort` (sets the demo
 * password without depending on `auth_password` directly).
 *
 * Verified by `DpopApplicationTests.modulithStructureIsValid`. Kotlin has no package
 * annotations, but `@ApplicationModule` is `@Target({PACKAGE, TYPE})` and meta-annotated
 * `@PackageInfo`, so this otherwise-unused type serves as the package descriptor - no
 * `package-info.java` and no Java source set needed.
 */
@ApplicationModule(allowedDependencies = ["tool_spi", "tool_api", "account"])
internal class ModuleMetadata
