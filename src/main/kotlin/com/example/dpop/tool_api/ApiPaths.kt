package com.example.dpop.tool_api

/**
 * The one place the API version is written down.
 *
 * It used to be a literal in 78 places across 33 controllers. `docs/05-api.md` says the API is
 * "versioned under /orchestrator/api/v1", but nothing made that one statement: introducing a v2
 * would have meant 78 copy-and-paste edits, and nothing would have noticed a typo in one of them.
 *
 * A `const val` is a compile-time constant, so it is usable inside annotations via string
 * templates: `@PostMapping("$API_V1/channels")`.
 *
 * Note what this does NOT do: it does not make two versions servable side by side. The response
 * envelope is shared by every endpoint, so a breaking change is global either way
 * (docs/05-api.md). What it does is make "v1" a decision with one location instead of 78.
 */
const val API_V1 = "/orchestrator/api/v1"
