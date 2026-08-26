package com.example.dpop.ext_stammdaten

import org.springframework.modulith.ApplicationModule

/**
 * External master-data stub - depends on nothing but the shared SPI; the orchestrator calls in,
 * never the other way round. `ExtStammdatenService` implements `tool_api.PersonDirectory`
 * directly (docs/04-orchestrierung.md #5), same as `AccountService` implements `AccountDirectory`.
 *
 * Verified by `DpopApplicationTests.modulithStructureIsValid`. Kotlin has no package
 * annotations, but `@ApplicationModule` is `@Target({PACKAGE, TYPE})` and meta-annotated
 * `@PackageInfo`, so this otherwise-unused type serves as the package descriptor - no
 * `package-info.java` and no Java source set needed.
 */
@ApplicationModule(allowedDependencies = ["tool_api"])
internal class ModuleMetadata
