package com.example.dpop.orchestrator.journey

import com.example.dpop.account.AccountProfile
import com.example.dpop.orchestrator.policy.Reachability
import com.example.dpop.orchestrator.policy.UnreachableReason
import com.example.dpop.orchestrator.session.AcrLevel
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.ToolCategory
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

    fun forIdentification(ctx: JourneyContext): List<ToolId> =
        ctx.filterAvailable(ctx.catalog.descriptors().filter { it.role.category == ToolCategory.IDENT }.map { it.toolId })

    /** Every tool that resolves the account itself from a submitted identifier. */
    fun forLookupLogin(ctx: JourneyContext): List<ToolId> =
        ctx.filterAvailable(ctx.catalog.descriptors().filter { it.role == MethodRole.LOOKUP_AUTH }.map { it.toolId })

    /**
     * The enrollment tool that turns an unconfirmed account email into a confirmed one - declared
     * by the module via [com.example.dpop.tool_spi.ToolDescriptor.confirmsAccountEmail], never
     * matched by toolId here.
     */
    fun forEmailConfirmation(ctx: JourneyContext): List<ToolId> =
        ctx.filterAvailable(ctx.catalog.descriptors().filter { it.confirmsAccountEmail }.map { it.toolId })

    /**
     * The device-bound AUTH tool for a credential that lives on THIS physical device, if the
     * account has one. A multi-instance credential (a non-extractable device key) structurally
     * cannot exist anywhere else, so this is both the fastest and the only offer that can succeed
     * without further input.
     */
    fun preferredDeviceAuth(account: AccountProfile, ctx: JourneyContext): ToolId? {
        val deviceAuthTools = ctx.catalog.descriptors()
            .filter { it.role == MethodRole.IDENTIFIED_AUTH && it.allowsMultipleInstances }
        val preferred = deviceAuthTools.firstOrNull { descriptor ->
            account.activeAuthenticationMethods.any {
                it.method == descriptor.method && descriptor.matchesCurrentOwner(it.details, ctx.bindingKeyRef, ctx.linkedAccountId, account.accountId)
            }
        }?.toolId
        return preferred?.takeIf { it in ctx.availableTools }
    }

    fun forAuth(account: AccountProfile, targetAcr: AcrLevel, ctx: JourneyContext): List<ToolId> =
        ctx.filterAvailable(ctx.policy.candidateTools(ctx.evidence, targetAcr, account, ctx.bindingKeyRef, ctx.linkedAccountId, ctx.availableTools))

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
                    // itself (ToolDescriptor.matchesCaller) - this generic layer never reads a
                    // concrete tool's own detail-map key.
                    if (descriptor.allowsMultipleInstances &&
                        !descriptor.matchesCurrentOwner(method.details, ctx.bindingKeyRef, ctx.linkedAccountId, account.accountId)
                    ) {
                        return@mapNotNull null
                    }
                    descriptor.toolId
                }
        )

    fun forEnrollment(account: AccountProfile, targetAcr: AcrLevel, ctx: JourneyContext): List<ToolId> =
        ctx.filterAvailable(ctx.policy.enrollmentCandidates(account, targetAcr))

    fun forReIdentification(targetAcr: AcrLevel, ctx: JourneyContext): List<ToolId> =
        ctx.filterAvailable(ctx.policy.reIdentCandidates(ctx.evidence, targetAcr))

    /**
     * The abort message once [forAuth] came back empty and re-identification isn't offered/possible
     * either - shared by every caller in that exact situation ([StepUpStrategy], [LookupLoginStrategy])
     * so none of them repeats the same bug: [AuthPolicy.reachability]'s `NotReachable` reason is
     * only meaningful "once the caller already knows there's no way through" - i.e. once
     * [AuthPolicy.reachability] itself came back `NotReachable`. [forAuth] coming back empty is a
     * DIFFERENT question (can THIS channel/session offer something RIGHT NOW - already-used-this-
     * session methods, a device-bound credential that doesn't match this physical device, tools
     * disabled via the demo's availability toggle, ...); an account can easily still be reachable
     * in principle while none of that applies here. Rendering the `NotReachable` reason
     * unconditionally on an empty [forAuth] result (the bug this closes) would produce a real,
     * coherent-looking, but factually WRONG explanation - e.g. "enrolled under a lower level" for
     * an account whose methods are enrolled at exactly the required level, because a completely
     * different, channel-local restriction was the actual blocker.
     */
    fun exhaustedAuthAbortReason(ctx: JourneyContext, account: AccountProfile, targetAcr: AcrLevel): String =
        when (val reachability = ctx.policy.reachability(account, targetAcr)) {
            is Reachability.NotReachable -> reachability.toAbortMessage()
            Reachability.Reachable ->
                "Das Konto könnte das geforderte Sicherheitsniveau grundsätzlich erreichen, aber auf diesem Kanal steht dafür gerade keine passende Methode zur Verfügung " +
                    "(z. B. bereits in dieser Sitzung genutzt, für dieses Gerät deaktiviert, oder an ein anderes Gerät gebunden)."
        }
}

/**
 * Renders a policy-level [UnreachableReason] into the user-facing (German) abort message - the
 * policy layer only ever names WHAT is missing (see that type's own doc); every caller that needs
 * to SHOW the reason (as opposed to just branching on [Reachability]) goes through this one place,
 * so the wording lives once, not once per call site.
 */
internal fun Reachability.NotReachable.toAbortMessage(): String =
    "Gefordertes Sicherheitsniveau ist mit den vorhandenen Methoden nicht erreichbar. ${reason.toGermanText()}"

private fun UnreachableReason.toGermanText(): String = when (this) {
    UnreachableReason.NoActiveMethod -> "Für dieses Konto ist derzeit kein aktives Anmeldeverfahren eingerichtet."

    is UnreachableReason.SingleFactorType ->
        "Die aktiven Verfahren (${methods.joinToString(", ")}) decken nur einen Faktor-Typ ab " +
            "(${factorTypes.joinToString(", ") { it.toGermanText() }}). Für dieses Sicherheitsniveau " +
            "ist zusätzlich ein Verfahren mit einem ANDEREN Faktor-Typ nötig, z. B. ein Passwort (Wissen), " +
            "wenn bisher nur Besitz-Verfahren wie SMS oder E-Mail aktiv sind."

    is UnreachableReason.CombinationCapped ->
        "Die aktiven Verfahren würden in Kombination reichen, wurden aber unter einem niedrigeren " +
            "Sicherheitsniveau eingerichtet ($maxEnrolledUnderAcr) - das begrenzt, wie hoch sie gemeinsam wirken " +
            "können. Ein neues Verfahren muss erst unter dem höheren Niveau eingerichtet werden."

    is UnreachableReason.SingleMethodCapped ->
        "Das aktive Verfahren ($method) würde für sich genommen reichen, wurde " +
            "aber unter einem niedrigeren Sicherheitsniveau eingerichtet ($maxEnrolledUnderAcr) - das begrenzt, " +
            "wie hoch es wirken kann, unabhängig davon, welche Faktor-Typen es abdeckt. Es muss erst unter dem " +
            "höheren Niveau erneut eingerichtet werden (z. B. direkt im Anschluss an eine Identifizierung oder " +
            "eine bereits ausreichende Kombination anderer Verfahren)."
}

private fun FactorType.toGermanText(): String = when (this) {
    FactorType.KNOWLEDGE -> "Wissen"
    FactorType.POSSESSION -> "Besitz"
    FactorType.INHERENCE -> "Inhärenz"
}
