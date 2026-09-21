package com.example.dpop.orchestrator.journey

import com.example.dpop.account.AccountProfile
import com.example.dpop.orchestrator.policy.CandidateContext
import com.example.dpop.orchestrator.policy.requiresSatisfied
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.ToolId

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

    /**
     * Availability (docs/03-tool-architektur.md) is applied here, at computation time, in addition
     * to [JourneyState.activatable] applying it again live on every read: this layer is what makes
     * a STRATEGY DECISION correct (e.g. "fall through to identification because nothing else is
     * left" vs. "offer these two") - activatable() alone can only re-narrow an already-chosen
     * state's offer, it cannot retroactively pick a different state shape.
     */
    private fun JourneyContext.filterAvailable(ids: List<ToolId>): List<ToolId> = ids.filter { it in availableTools }

    private fun JourneyContext.candidateContext(targetAcr: AcrLevel, account: AccountProfile? = null): CandidateContext =
        CandidateContext(
            evidence = evidence,
            requiredAcr = targetAcr,
            account = account,
            bindingKeyRef = bindingKeyRef,
            linkedAccountId = linkedAccountId,
            availableTools = availableTools
        )

    /**
     * Identification proper - [MethodRole.IDENTIFICATION] only, never a
     * [MethodRole.CORRELATION] step: the latter proves nothing on its own and must not show up
     * as a way to identify (ADR-18). Matching on the role, not the category, for the same reason
     * `authCandidates` does: `category == IDENT` alone matches both.
     *
     * `requires` is applied here too, not just for enrollment: a tool whose preconditions the
     * account doesn't meet must not be offered (nor be activatable by a direct call).
     */
    fun forIdentification(ctx: JourneyContext): List<ToolId> = identCandidates(ctx, MethodRole.IDENTIFICATION)

    /** The mirror image: the correlation steps [forIdentification] deliberately leaves out. */
    fun forAssignment(ctx: JourneyContext): List<ToolId> = identCandidates(ctx, MethodRole.CORRELATION)

    private fun identCandidates(ctx: JourneyContext, role: MethodRole): List<ToolId> =
        ctx.filterAvailable(
            ctx.catalog.descriptors()
                .filter { it.role == role }
                .filter { descriptor -> descriptor.requires.all { requiresSatisfied(it, ctx.account) } }
                .map { it.toolId }
        )

    /** Every tool that resolves the account itself from a submitted identifier. */
    fun forLookupLogin(ctx: JourneyContext): List<ToolId> =
        ctx.filterAvailable(ctx.catalog.descriptors().filter { it.role == MethodRole.LOOKUP_AUTH }.map { it.toolId })

    /**
     * The enrollment tool that asserts a confirmed email about its subject - declared by the
     * module via [com.example.dpop.tool_spi.ToolDescriptor.claims] (an EMAIL
     * [com.example.dpop.tool_spi.ClaimDeclaration]), never matched by toolId here.
     */
    fun forEmailConfirmation(ctx: JourneyContext): List<ToolId> =
        ctx.filterAvailable(ctx.catalog.descriptors().filter { it.claims.any { c -> c.attributeType == AttributeType.EMAIL } }.map { it.toolId })

    /**
     * The AUTH tool for a credential that lives on THIS physical device, if the account has one
     * ([ToolDescriptor.keyBinding]). Such a credential structurally cannot exist anywhere
     * else, so this is both the fastest and the only offer that can succeed without further
     * input.
     */
    fun preferredDeviceAuth(account: AccountProfile, ctx: JourneyContext): ToolId? {
        val deviceAuthTools = ctx.catalog.descriptors()
            .filter { it.role == MethodRole.IDENTIFIED_AUTH && it.keyBinding != null }
        val preferred = deviceAuthTools.firstOrNull { descriptor ->
            account.activeAuthenticationMethods.any {
                it.method == descriptor.method && descriptor.usableByCaller(it.details, ctx.bindingKeyRef, ctx.linkedAccountId, account.accountId)
            }
        }?.toolId
        return preferred?.takeIf { it in ctx.availableTools }
    }

    fun forAuth(account: AccountProfile, targetAcr: AcrLevel, ctx: JourneyContext): List<ToolId> =
        ctx.filterAvailable(ctx.policy.authCandidates(ctx.candidateContext(targetAcr, account)))

    /**
     * Every active IDENTIFIED_AUTH method the account has, for a fresh "prove you're still you"
     * re-confirmation (e.g. before deleting the account) - deliberately NOT [forAuth]: that one
     * excludes methods already proven this session (`evidence.amr`), because STEP_UP needs
     * additional assurance. A re-confirmation needs the opposite - re-presenting the very same,
     * already-used factor is a perfectly valid answer to "are you still there right now?". No acr
     * target either: any active factor counts, regardless of the level it reaches.
     */
    fun forReconfirmation(account: AccountProfile, ctx: JourneyContext): List<ToolId> =
        ctx.filterAvailable(
            ctx.catalog.descriptors()
                .filter { it.role == MethodRole.IDENTIFIED_AUTH }
                .mapNotNull { descriptor ->
                    val method = account.activeAuthenticationMethods.firstOrNull { it.method == descriptor.method }
                        ?: return@mapNotNull null
                    // Same device-binding rule as ordinary candidate resolution: a non-extractable
                    // device key structurally cannot exist anywhere else than the device it was
                    // enrolled on (docs/03-tool-architektur.md). Delegated to the descriptor
                    // itself (ToolDescriptor.keyBinding) - this generic layer never reads a
                    // concrete tool's own detail-map key.
                    if (!descriptor.usableByCaller(method.details, ctx.bindingKeyRef, ctx.linkedAccountId, account.accountId)) {
                        return@mapNotNull null
                    }
                    descriptor.toolId
                }
        )

    fun forEnrollment(account: AccountProfile, targetAcr: AcrLevel, ctx: JourneyContext): List<ToolId> =
        ctx.filterAvailable(ctx.policy.enrollmentCandidates(ctx.candidateContext(targetAcr, account)))

    fun forReIdentification(targetAcr: AcrLevel, ctx: JourneyContext): List<ToolId> =
        ctx.filterAvailable(ctx.policy.reIdentCandidates(ctx.candidateContext(targetAcr)))
}
