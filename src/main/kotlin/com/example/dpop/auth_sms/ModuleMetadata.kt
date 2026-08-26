package com.example.dpop.auth_sms

import org.springframework.modulith.ApplicationModule

/**
 * A method module talks to the orchestrator through tool_spi and tool_api only - it never reads
 * account or another method module (docs/03-tool-architektur.md #2). Its controllers
 * (`auth_sms.api.v1`) live here too, reaching the orchestrator through
 * `tool_api.ToolEndpoint`/`AccountDirectory` alone (docs/04-orchestrierung.md #5, DPoP-demo-2tm) -
 * the orchestrator no longer needs to know `auth_sms` exists.
 *
 * Verified by `DpopApplicationTests.modulithStructureIsValid`. Kotlin has no package
 * annotations, but `@ApplicationModule` is `@Target({PACKAGE, TYPE})` and meta-annotated
 * `@PackageInfo`, so this otherwise-unused type serves as the package descriptor - no
 * `package-info.java` and no Java source set needed.
 */
@ApplicationModule(allowedDependencies = ["tool_spi", "tool_api"])
internal class ModuleMetadata
