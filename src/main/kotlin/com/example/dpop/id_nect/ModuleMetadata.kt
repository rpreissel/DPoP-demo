package com.example.dpop.id_nect

import org.springframework.modulith.ApplicationModule

/**
 * Identification through Nect (docs/ideen/ident-nect.md). Like every method module it reaches the
 * orchestrator through `tool_spi`/`tool_api` alone; one further edge, declared rather than
 * tolerated: the identification service itself (`nect_mock.NectIdent`), the same shape as
 * `auth_kobil -> kobil_mock` and `id_fsc -> ext_personenverzeichnis` (ADR-31). Swapping the mock for the
 * real service changes that edge's target, not the tool.
 *
 * Verified by `DpopApplicationTests.modulithStructureIsValid`.
 */
@ApplicationModule(allowedDependencies = ["tool_spi", "tool_api", "nect_mock", "texts"])
internal class ModuleMetadata
