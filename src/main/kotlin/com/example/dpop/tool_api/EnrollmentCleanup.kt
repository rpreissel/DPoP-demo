package com.example.dpop.tool_api

import com.example.dpop.tool_spi.EnrollmentRef

/**
 * Deletes a method module's own long-lived credential row - the counterpart to [AccountDirectory]
 * (read-only lookups) for the one write a method module must expose across the module boundary:
 * account deletion (docs/05-api.md, Account löschen). Neither `account` nor `orchestrator` may
 * depend on a method module by name (docs/03-tool-architektur.md), so account deletion collects
 * every [EnrollmentCleanup] bean Spring can see - regardless of which module declares it - and
 * dispatches by [enrollmentType], exactly the way [com.example.dpop.orchestrator.tool.ToolHandlerRegistry]
 * aggregates every module's [com.example.dpop.tool_spi.ToolDescriptor].
 *
 * A method module without a long-lived credential table (`auth_email` - the confirmed address
 * lives directly on `Account`) needs no implementation at all.
 */
interface EnrollmentCleanup {
    /** Matches [EnrollmentRef.type] as written by this module's own enrollment handler. */
    val enrollmentType: String

    fun delete(enrollmentRef: EnrollmentRef)
}
