package com.example.dpop.tool_api

import org.springframework.modulith.ApplicationModule

/**
 * Package descriptor for Spring Modulith - depends on nothing but `tool_spi`, so any module can
 * depend on `tool_api` without pulling in the orchestrator. Verified by
 * `DpopApplicationTests.modulithStructureIsValid`.
 *
 * `@ApplicationModule` is `@Target({PACKAGE, TYPE})` and meta-annotated `@PackageInfo`; Kotlin has
 * no package annotations, so this otherwise-unused type carries it instead. A `package-info.kt`
 * with only `@file:ApplicationModule` does not work: a Kotlin file with no declarations compiles
 * to no class at all, so the annotation would be silently discarded.
 */
@ApplicationModule(allowedDependencies = ["tool_spi"])
internal class ModuleMetadata
