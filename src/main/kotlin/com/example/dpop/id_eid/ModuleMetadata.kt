package com.example.dpop.id_eid

import org.springframework.modulith.ApplicationModule

/**
 * A method module talks to the orchestrator through tool_spi and tool_api only - it never reads
 * account or another method module (docs/03-tool-architektur.md #2). Its controller
 * (`id_eid.api.v1.IdentEidToolController` - `api.v1` mirrors the orchestrator's own package shape,
 * since the URL is still `/orchestrator/api/v1/...` regardless of which module owns the class)
 * lives here too, reaching the orchestrator through `tool_api.ToolEndpoint`/`PersonDirectory`
 * alone - the orchestrator no longer needs to know `id_eid` exists.
 *
 * Verified by `DpopApplicationTests.modulithStructureIsValid`. Kotlin has no package
 * annotations, but `@ApplicationModule` is `@Target({PACKAGE, TYPE})` and meta-annotated
 * `@PackageInfo`, so this otherwise-unused type serves as the package descriptor - no
 * `package-info.java` and no Java source set needed.
 */
@ApplicationModule(allowedDependencies = ["tool_spi", "tool_api"])
internal class ModuleMetadata
