package com.example.dpop.tool_spi

/**
 * The exhaustive set of roles a tool can play with respect to a `method` (docs/03-tool-architektur.md).
 * `(method, role)` is the real, unambiguous key for "the concrete procedure of this kind for this
 * credential" - `(method, category)` alone is NOT unique (both [DEVICE_AUTH] and [LOOKUP_AUTH]
 * share `category=AUTH`), which is exactly the ambiguity `role` replaces. [ToolDescriptor.category]
 * is derived from this, never set independently, so the two can never drift apart.
 */
enum class MethodRole {
    /** e.g. ident-fsc - resolves identity, never a durable authentication method. */
    IDENTIFICATION,

    /** e.g. enroll-sms - creates a new credential for `method`. */
    ENROLLMENT,

    /**
     * e.g. auth-sms, auth-device - proves a credential already known via the channel/process.
     * Assumes the account is already resolved before activation (docs/04-orchestrierung.md).
     */
    DEVICE_AUTH,

    /**
     * e.g. auth-sms-lookup - proves the SAME underlying credential as its [DEVICE_AUTH] sibling,
     * but resolves the account itself from a submitted email instead of already knowing it via
     * the channel (docs/04-orchestrierung.md, lookup-based login). Only ever reachable through the
     * dedicated lookup-login entry point, never through AuthPolicy.candidateTools' generic
     * per-method resolution.
     */
    LOOKUP_AUTH
}
