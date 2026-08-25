package com.example.dpop.account

import org.springframework.modulith.ApplicationModule

/**
 * The account module must never depend on a method module. That is what keeps auth_email -> account (see that module) acyclic and therefore permissible at all.
 *
 * Verified by `DpopApplicationTests.modulithStructureIsValid`. Kotlin has no package
 * annotations, but `@ApplicationModule` is `@Target({PACKAGE, TYPE})` and meta-annotated
 * `@PackageInfo`, so this otherwise-unused type serves as the package descriptor - no
 * `package-info.java` and no Java source set needed.
 */
@ApplicationModule(allowedDependencies = ["tool_spi"])
internal class ModuleMetadata
