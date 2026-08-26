package com.example.dpop.tool_api

import org.springframework.modulith.ApplicationModule

/**
 * The response envelope every tool controller returns, and - once `DPoP-demo-2tm` finishes - the
 * SPI a tool controller is written against instead of the orchestrator directly
 * (docs/04-orchestrierung.md #5, `JourneyApi`). Depends on nothing but `tool_spi`, so a method
 * module can depend on it without depending on the orchestrator - which is the whole point: today
 * every method module still reaches its controller code through `orchestrator.api.v1.tool`, an
 * edge this module exists to remove one controller at a time.
 *
 * A tool controller never constructs a [com.example.dpop.tool_api.ChannelResponse] piece by
 * piece - it gets one back, fully built, from whatever implements the eventual `ToolEndpoint`.
 * These types are the shape of that answer, not a kit for assembling one.
 *
 * Verified by `DpopApplicationTests.modulithStructureIsValid`. Kotlin has no package
 * annotations, but `@ApplicationModule` is `@Target({PACKAGE, TYPE})` and meta-annotated
 * `@PackageInfo`, so this otherwise-unused type serves as the package descriptor. A
 * `package-info.kt` carrying only `@file:ApplicationModule` does NOT work: a Kotlin file without
 * declarations compiles to no class at all, so the annotation is silently discarded
 * (docs/08-projektrahmen.md, DPoP-demo-7ae).
 */
@ApplicationModule(allowedDependencies = ["tool_spi"])
internal class ModuleMetadata
