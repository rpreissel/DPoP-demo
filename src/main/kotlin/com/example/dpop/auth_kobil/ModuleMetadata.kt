package com.example.dpop.auth_kobil

import org.springframework.modulith.ApplicationModule

/**
 * A method module talks to the orchestrator through tool_spi and tool_api only
 * (docs/03-tool-architektur.md #2). What makes this one different from every other method module
 * is the fourth allowed edge: `kobil_mock`, the simulated external provider. That edge is the
 * point of the module - a KOBIL credential is not something this backend can verify on its own,
 * it is something KOBIL asserts and we redeem. Declaring the dependency makes that visible in the
 * module graph instead of hiding it behind a port with only one implementation.
 *
 * Verified by `DpopApplicationTests.modulithStructureIsValid`. Kotlin has no package annotations,
 * but `@ApplicationModule` is `@Target({PACKAGE, TYPE})` and meta-annotated `@PackageInfo`, so
 * this otherwise-unused type serves as the package descriptor.
 */
@ApplicationModule(allowedDependencies = ["tool_spi", "tool_api", "kobil_mock", "texts"])
internal class ModuleMetadata
