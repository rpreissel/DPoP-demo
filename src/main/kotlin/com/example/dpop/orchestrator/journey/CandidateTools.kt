package com.example.dpop.orchestrator.journey

import com.example.dpop.account.AccountProfile
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.ToolCategory

/**
 * Which tools from the catalog qualify for a given kind of offer. Everything is DERIVED from the
 * descriptors the modules register (docs/03-tool-architektur.md #1: the aggregation of all
 * descriptors IS the catalog) - no toolId is ever spelled out here, so a new method joins an offer
 * by declaring its role.
 *
 * A strategy asks these questions; the ANSWER then travels in a [JourneyState], which is what
 * actually holds an offer. This object holds no state of its own.
 */
internal object CandidateTools {

    fun forIdentification(ctx: JourneyContext): List<String> =
        ctx.catalog.descriptors().filter { it.category == ToolCategory.IDENT }.map { it.toolId }

    /** Every tool that resolves the account itself from a submitted identifier. */
    fun forLookupLogin(ctx: JourneyContext): List<String> =
        ctx.catalog.descriptors().filter { it.role == MethodRole.LOOKUP_AUTH }.map { it.toolId }

    /**
     * The enrollment tool that turns an unconfirmed account email into a confirmed one - declared
     * by the module via [com.example.dpop.tool_spi.ToolDescriptor.confirmsAccountEmail], never
     * matched by toolId here.
     */
    fun forEmailConfirmation(ctx: JourneyContext): List<String> =
        ctx.catalog.descriptors().filter { it.confirmsAccountEmail }.map { it.toolId }

    /**
     * The device-bound AUTH tool for a credential that lives on THIS physical device, if the
     * account has one. A multi-instance credential (a non-extractable device key) structurally
     * cannot exist anywhere else, so this is both the fastest and the only offer that can succeed
     * without further input.
     */
    fun preferredDeviceAuth(account: AccountProfile, ctx: JourneyContext): String? {
        val deviceAuthTools = ctx.catalog.descriptors()
            .filter { it.role == MethodRole.DEVICE_AUTH && it.allowsMultipleInstances }
        return deviceAuthTools.firstOrNull { descriptor ->
            account.activeAuthenticationMethods.any {
                it.method == descriptor.method && it.details?.get("deviceBindingKeyRef") == ctx.bindingKeyRef
            }
        }?.toolId
    }

    fun forAuth(account: AccountProfile, targetAcr: String, ctx: JourneyContext): List<String> =
        ctx.policy.candidateTools(ctx.evidence, targetAcr, account, ctx.bindingKeyRef)

    fun forEnrollment(account: AccountProfile, targetAcr: String, ctx: JourneyContext): List<String> =
        ctx.policy.enrollmentCandidates(account, targetAcr)
}
