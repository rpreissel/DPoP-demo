package com.example.dpop.tool_api

/**
 * The one place the API version is written down.
 *
 * `docs/05-api.md` says the API is "versioned under /orchestrator/api/v1"; this constant makes that
 * one statement in code. As a literal in every controller, introducing a v2 would mean dozens of
 * copy-and-paste edits, and nothing would notice a typo in one of them.
 *
 * A `const val` is a compile-time constant, so it is usable inside annotations via string
 * templates: `@PostMapping("$API_V1/channels")`.
 *
 * Note what this does NOT do: it does not make two versions servable side by side. The response
 * envelope is shared by every endpoint, so a breaking change is global either way
 * (docs/05-api.md). What it does is make "v1" a decision with one location.
 */
const val API_V1 = "/orchestrator/api/v1"
