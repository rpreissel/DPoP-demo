package com.example.dpop.tool_spi

import org.springframework.modulith.ApplicationModule

/**
 * Package descriptor for Spring Modulith. This module depends on nothing but the `texts` library
 * (a failure carries a [com.example.dpop.texts.Text]), so both the orchestrator and every method
 * module can depend on it without depending on each other.
 * Verified by `DpopApplicationTests.modulithStructureIsValid`.
 *
 * `@ApplicationModule` is `@Target({PACKAGE, TYPE})` and meta-annotated `@PackageInfo`; Kotlin has
 * no package annotations, so this otherwise-unused type carries it instead.
 */
@ApplicationModule(allowedDependencies = ["texts"])
internal class ModuleMetadata
