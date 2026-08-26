package com.example.dpop.tool_api

/**
 * Resolves an identification tool's own identifier (e.g. a KVNR) to the external person id the
 * orchestrator needs to pass on to `ToolOutcome.Completed.Identified` - never the person's
 * master data itself (docs/04-orchestrierung.md #5).
 *
 * Implemented directly by `ExtStammdatenService`, the same way `AccountService` implements
 * [AccountDirectory]. Named for `id_fsc` specifically, not a generic identifier resolver: whether
 * a second identification method (e.g. `ident-eid`) shares this port or needs its own is a
 * decision for when that second case actually exists, not one to generalize for up front
 * (docs/08-projektrahmen.md A11).
 */
interface PersonDirectory {
    fun findPersonIdByKvnr(kvnr: String): Long?
}
