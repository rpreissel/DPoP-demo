package com.example.dpop.ext_personenverzeichnis

import org.springframework.modulith.ApplicationModule

/**
 * External master-data stub - depends on nothing but the shared SPI and the `texts` library (ADR-33); the orchestrator calls in,
 * never the other way round. `Personenverzeichnis` implements `tool_api.PersonDirectory`
 * directly (docs/04-orchestrierung.md #5), same as `AccountService` implements `AccountDirectory`.
 *
 * A second face, like `kobil_mock.KobilSsms`: [Freischaltcodes] - the register issues the
 * Freischaltcodes, so `id_fsc` asks it directly whether one is valid (ADR-31).
 *
 * Verified by `DpopApplicationTests.modulithStructureIsValid`. Kotlin has no package
 * annotations, but `@ApplicationModule` is `@Target({PACKAGE, TYPE})` and meta-annotated
 * `@PackageInfo`, so this otherwise-unused type serves as the package descriptor - no
 * `package-info.java` and no Java source set needed.
 */
@ApplicationModule(allowedDependencies = ["tool_spi", "tool_api", "texts"])
internal class ModuleMetadata
