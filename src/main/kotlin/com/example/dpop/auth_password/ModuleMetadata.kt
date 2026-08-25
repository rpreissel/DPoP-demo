package com.example.dpop.auth_password

import org.springframework.modulith.ApplicationModule

/**
 * A method module talks to the orchestrator through tool_spi only - it never reads account or another method module (docs/03-tool-architektur.md #2).
 *
 * Verified by `DpopApplicationTests.modulithStructureIsValid`. Kotlin has no package
 * annotations, but `@ApplicationModule` is `@Target({PACKAGE, TYPE})` and meta-annotated
 * `@PackageInfo`, so this otherwise-unused type serves as the package descriptor - no
 * `package-info.java` and no Java source set needed.
 */
@ApplicationModule(allowedDependencies = ["tool_spi"])
internal class ModuleMetadata
