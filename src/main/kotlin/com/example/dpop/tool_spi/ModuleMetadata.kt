package com.example.dpop.tool_spi

import org.springframework.modulith.ApplicationModule

/**
 * The shared SPI depends on nothing at all - it must never learn about a concrete method
 * (docs/03-tool-architektur.md #2: each module brings its own MethodFamily). Handler classes
 * implement ToolDescriptor directly; there is no separate wrapper interface.
 *
 * Not one of the M1-M5 modules in docs/08-projektrahmen.md: a pure SPI package with no
 * dependencies of its own, so that both the orchestrator and the method modules can depend on it
 * without depending back on the orchestrator - which "ist das einzige Modul, das die anderen
 * Module referenzieren darf", so a module referencing it while it references back would cycle.
 *
 * Verified by `DpopApplicationTests.modulithStructureIsValid`. Kotlin has no package
 * annotations, but `@ApplicationModule` is `@Target({PACKAGE, TYPE})` and meta-annotated
 * `@PackageInfo`, so this otherwise-unused type serves as the package descriptor - no
 * `package-info.java` and no Java source set needed.
 */
@ApplicationModule(allowedDependencies = [])
internal class ModuleMetadata
