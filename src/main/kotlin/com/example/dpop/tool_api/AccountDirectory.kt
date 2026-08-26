package com.example.dpop.tool_api

import com.example.dpop.tool_spi.EnrollmentRef

/**
 * The only account knowledge a tool controller may have: opaque handles, resolved by the
 * orchestrator, never a profile or its stored details (docs/03-tool-architektur.md #2,
 * docs/04-orchestrierung.md #5). A controller asks "which account, which credential" - what an
 * account actually contains, and how a credential reference is stored inside it, stays inside the
 * `account` module.
 *
 * Implemented directly by `AccountService` (the `account` module depending on `tool_api` is safe:
 * `tool_api` is the shared SPI, not a method module, so this creates no cycle) - there is no
 * separate orchestrator-side wrapper for this port.
 */
interface AccountDirectory {
    /** Lookup-based tools (auth-*-lookup) resolve the account themselves from a submitted identifier. */
    fun resolveAccountByEmail(email: String): Long?

    /** The account's active, enrolled credential for [method] - null if none is set up. */
    fun activeEnrollment(accountId: Long, method: String): EnrollmentRef?

    /**
     * The one active instance of a multi-instance method (docs/03-tool-architektur.md,
     * allowsMultipleInstances) that lives on THIS physical device - never just any instance, or a
     * device could be offered a credential it structurally cannot use.
     */
    fun activeDeviceEnrollment(accountId: Long, method: String, deviceBindingKeyRef: String): EnrollmentRef?
}
