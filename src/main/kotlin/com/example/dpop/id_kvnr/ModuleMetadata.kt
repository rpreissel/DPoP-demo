package com.example.dpop.id_kvnr

import org.springframework.modulith.ApplicationModule

/**
 * A method module talks to the orchestrator through tool_spi and tool_api only - it never reads
 * account or another method module (docs/03-tool-architektur.md #2). Its controller
 * (`id_kvnr.api.v1.IdentKvnrToolController`) lives here too, reaching the orchestrator through
 * `tool_api.ToolEndpoint`/`PersonDirectory` alone (docs/04-orchestrierung.md #5).
 *
 * Notably it does NOT depend on `id_eid`, although it only ever runs after an attestation: the
 * link between the two is the account's claims plus this tool's `requires`, never a direct
 * module reference - so any future attestation procedure (EUDI wallet) feeds it unchanged
 * (docs/12-entscheidungen.md ADR-18).
 *
 * Verified by `DpopApplicationTests.modulithStructureIsValid`.
 */
@ApplicationModule(allowedDependencies = ["tool_spi", "tool_api", "texts"])
internal class ModuleMetadata
