package com.example.dpop.nect_mock

import org.springframework.modulith.ApplicationModule

/**
 * The simulated identification service Nect - a stand-in for a *foreign* system, not a tool
 * module, exactly like `kobil_mock`: it depends on nothing but the `texts` library (its own wordings, ADR-33), knows no journey, no account, no
 * register. Two faces, as the real service has: [NectIdent] for the relying party's backend
 * (`id_nect`), and the HTTP face `/mock-nect/...` for its jump page (`/nect/`), where the user picks
 * eID, ePass or EUDI wallet (docs/03-tool-architektur.md, ident-nect).
 *
 * Verified by `DpopApplicationTests.modulithStructureIsValid`.
 */
@ApplicationModule(allowedDependencies = ["texts", "demo_mode"])
internal class ModuleMetadata
