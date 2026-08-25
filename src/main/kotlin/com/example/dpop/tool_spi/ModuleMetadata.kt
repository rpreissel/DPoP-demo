package com.example.dpop.tool_spi

import org.springframework.modulith.ApplicationModule

/**
 * The shared SPI depends on nothing at all - it must never learn about a concrete method (docs/03-tool-architektur.md #2: each module brings its own MethodFamily).
 *
 * Verified by `DpopApplicationTests.modulithStructureIsValid`. Kotlin has no package
 * annotations, but `@ApplicationModule` is `@Target({PACKAGE, TYPE})` and meta-annotated
 * `@PackageInfo`, so this otherwise-unused type serves as the package descriptor - no
 * `package-info.java` and no Java source set needed.
 */
@ApplicationModule(allowedDependencies = [])
internal class ModuleMetadata
