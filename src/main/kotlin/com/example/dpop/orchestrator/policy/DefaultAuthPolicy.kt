package com.example.dpop.orchestrator.policy

import com.example.dpop.account.AccountProfile
import com.example.dpop.account.AuthMethodView
import com.example.dpop.orchestrator.kernel.AcrLevels
import com.example.dpop.orchestrator.tool.ToolHandlerRegistry
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.TrustLevel
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.ClaimRequirement
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.ToolCategory
import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ToolId
import org.springframework.stereotype.Component

/**
 * Provisional default implementation (docs/08-projektrahmen.md, Phase B3): the amr->acr
 * mapping is fachlich/regulatorisch offen (docs/04-orchestrierung.md #2) and only stubbed
 * here via each tool's own maxAcr, plus one concrete combination rule: two AUTH tools of
 * DIFFERENT factor types, proven together, earn one tier above what either could reach alone
 * (e.g. sms + password, both loa1 individually, reach loa2 as MFA). A single tool that already
 * proves >=2 factor types on its own (e.g. a hypothetical passkey with user verification) is
 * NOT bumped again - its maxAcr already prices that in.
 *
 * The bump itself is capped: it can never exceed the highest loa any of the combining methods
 * was itself enrolled under (docs/06-ablaeufe.md #1's enrolledUnderAcr capping, extended to
 * combinations) - otherwise a compromised low-trust session could add two weak factors and
 * self-escalate past anything ever actually proven. In the common case (both methods enrolled
 * right after a loa2 identification) this is no restriction at all; it only bites when a
 * combination was assembled entirely within a loa1 session.
 *
 * MFA is additionally, unconditionally required from loa3 upward regardless of catalog
 * combinations.
 *
 * `resolveAcr` splits its input into two independently computed levels before combining them: IAL
 * ([identityAssuranceLevel], "who is this?" - an IDENTIFICATION tool's own loa, THIS session only,
 * see that function's doc for why an account's past `account.change_log.acr` (IDENTIFIED) is deliberately NOT
 * also consulted here) and AAL ([authenticatorAssuranceLevel], "how strong is the proof at THIS
 * login?" - the MFA-bump logic above, restricted to [EvidenceAxis.AUTHENTICATOR] evidence only).
 * The result is simply `max(IAL, AAL)` - a single tool reaching loa2/loa3 on either axis alone
 * (e.g. `ident-fsc`'s own maxAcr, `ident-eid`'s own two factor types, or a passkey's own two
 * factor types) still reaches that level overall; what the split actually prevents is an
 * IDENTIFICATION combining with an unrelated AUTHENTICATOR factor to manufacture a false MFA
 * bump (see [applyMfaBump]'s doc).
 */
@Component
class DefaultAuthPolicy(private val toolRegistry: ToolHandlerRegistry) : AuthPolicy {

    override fun resolveAcr(evidence: AuthEvidence, account: AccountProfile?): AcrLevel =
        AcrLevel.max(identityAssuranceLevel(evidence), authenticatorAssuranceLevel(evidence))

    /**
     * IAL: the highest loa any IDENTIFICATION has established THIS session ([evidence]'s own
     * [EvidenceAxis.IDENTITY] entries) - deliberately NOT also falling back to [AccountProfile]'s
     * persisted `account.change_log.acr` (IDENTIFIED) from a past session: `AuthEvidence` is one-per-channel,
     * cleared at logout, precisely so identity must be re-proven per session
     * (`orchestrator.session.AuthEvidence`'s own class doc) - a fresh channel on a device that was
     * never re-identified stays at whatever THIS session's own IDENTITY evidence says, "none" if
     * there is none, regardless of what the account achieved in some earlier, unrelated session
     * (`MfaCombinationIntegrationTest`: "no re-identification, so fsc's own loa2 isn't in play this
     * time"). `account.change_log.acr` (IDENTIFIED)'s OWN role in the three-way cap (ADR-5) already happens
     * upstream of this - baked into `enrolledUnderAcr` at the moment a method was enrolled - not
     * re-applied here a second time.
     */
    private fun identityAssuranceLevel(evidence: AuthEvidence): AcrLevel {
        val reachable = evidence.factors.filter { it.axis == EvidenceAxis.IDENTITY }
            .maxOfOrNull { AcrLevel.rank(it.loa) } ?: return AcrLevel.NONE
        return AcrLevel.levelAt(reachable)
    }

    /**
     * AAL: exactly the pre-existing [baseAcr]/[applyMfaBump] combination logic, but restricted to
     * [EvidenceAxis.AUTHENTICATOR] entries - an IDENTIFICATION's loa/factor type must never leak
     * into "how strong is the authenticator proof for THIS login", see class doc.
     */
    private fun authenticatorAssuranceLevel(evidence: AuthEvidence): AcrLevel {
        val authenticatorFactors = evidence.factors.filter { it.axis == EvidenceAxis.AUTHENTICATOR }
        return applyMfaBump(baseAcr(authenticatorFactors), AuthEvidence(authenticatorFactors))
    }

    override fun isSatisfied(evidence: AuthEvidence, requiredAcr: AcrLevel, account: AccountProfile?): Boolean {
        val levelOk = AcrLevel.rank(resolveAcr(evidence, account)) >= AcrLevel.rank(requiredAcr)
        // Checked PER AXIS, never as one union across both: a single tool covering >=2 factor
        // types on its own axis is self-contained MFA (e.g. ident-eid: card + PIN in one run,
        // id_eid/Descriptors.kt) - but an IDENTITY factor type must still never combine with a
        // separate, unrelated AUTHENTICATOR factor type to jointly manufacture MFA (see class doc).
        val identityFactorTypes = evidence.factors.filter { it.axis == EvidenceAxis.IDENTITY }.flatMap { it.factorTypes }.toSet()
        val authenticatorFactorTypes = evidence.factors.filter { it.axis == EvidenceAxis.AUTHENTICATOR }.flatMap { it.factorTypes }.toSet()
        val mfaOk = !requiresMfa(requiredAcr) || identityFactorTypes.size >= 2 || authenticatorFactorTypes.size >= 2
        return levelOk && mfaOk
    }

    override fun reachability(account: AccountProfile, requiredAcr: AcrLevel): Reachability {
        val active = account.authenticationMethods.filter { it.active }
        if (active.isEmpty()) return Reachability.NotReachable(UnreachableReason.NoActiveMethod)

        val descriptors = active.mapNotNull { m -> descriptorFor(m.method)?.let { m to it } }
        val factorTypesUnion = descriptors.flatMap { it.second.factorTypes }.toSet()
        val distinctMethods = descriptors.map { it.second.method }.distinct().size
        val bestAcr = descriptors
            .map { (m, d) -> AcrLevel.min(AcrLevel.of(m.enrolledUnderAcr), d.maxAcr) }
            .maxByOrNull { AcrLevel.rank(it) }
            ?: AcrLevel.NONE
        val maxEnrolledUnderAcr = active.maxOfOrNull { AcrLevel.rank(AcrLevel.of(it.enrolledUnderAcr)) }?.let { AcrLevel.levelAt(it) } ?: AcrLevel.NONE
        val effectiveAcr = combinedAcr(bestAcr, distinctMethods, factorTypesUnion, maxEnrolledUnderAcr)

        val levelOk = AcrLevel.rank(effectiveAcr) >= AcrLevel.rank(requiredAcr)
        val mfaOk = !requiresMfa(requiredAcr) || factorTypesUnion.size >= 2
        if (levelOk && mfaOk) return Reachability.Reachable

        // Gated on factor-type coverage ALONE, never also on distinctMethods (unlike combinedAcr's
        // bump condition, which is deliberately about something else - see its own doc): a single
        // method that already covers >=2 factor types on its own (e.g. `device`) is just as
        // factor-diverse as two combined single-factor ones, so "you need a method of a different
        // factor type" would be a wrong, self-contradicting explanation for it (it would list that
        // very method's own multiple factor types right after claiming it covers only one). Such a
        // method's real ceiling - if reached at all - is the enrolledUnderAcr cap below, never this.
        if (factorTypesUnion.size < 2) {
            val methodNames = descriptors.map { it.second.method }.distinct()
            return Reachability.NotReachable(UnreachableReason.SingleFactorType(methodNames, factorTypesUnion))
        }

        val cap = maxEnrolledUnderAcr
        return Reachability.NotReachable(
            if (distinctMethods >= 2) UnreachableReason.CombinationCapped(cap)
            else UnreachableReason.SingleMethodCapped(descriptors.first().second.method, cap)
        )
    }

    override fun enrollmentCandidates(ctx: CandidateContext): List<ToolId> {
        val account = checkNotNull(ctx.account) { "enrollmentCandidates requires an account in CandidateContext" }
        val activeMethods = account.authenticationMethods.filter { it.active }.map { it.method }.toSet()
        return toolRegistry.descriptors()
            .filter { it.role.category == ToolCategory.ENROLL }
            // Singleton methods disappear from the offer once active; multi-instance methods
            // (device) keep being offered - a NEW physical device can always add its own instance
            // even though other devices already have theirs (docs/03-tool-architektur.md).
            .filter { it.method !in activeMethods || it.allowsMultipleInstances }
            .filter { it.requires.all { requirement -> requiresSatisfied(requirement, account) } }
            .map { it.toolId }
    }

    override fun authCandidates(ctx: CandidateContext): List<ToolId> {
        val evidence = ctx.evidence
        val requiredAcr = ctx.requiredAcr
        val account = checkNotNull(ctx.account) { "authCandidates requires an account in CandidateContext" }
        val bindingKeyRef = ctx.bindingKeyRef
        val linkedAccountId = ctx.linkedAccountId
        val availableTools = ctx.availableTools
        val usedMethods = evidence.factors.map { it.method.value }.toSet()
        val active = account.authenticationMethods.filter { it.active }

        // A session with an already-known account must always be offered the IDENTIFIED_AUTH tool
        // for a method, never a LOOKUP_AUTH sibling that expects to resolve the account itself
        // from a submitted email (docs/03-tool-architektur.md). role (not category) is the
        // key that actually distinguishes the two - category=AUTH alone matches both.
        //
        // Filtered down to what's actually OFFERABLE right here (availableTools this channel
        // declared, plus the same device-ownership check `remaining` below applies) BEFORE
        // computing singleMethodSuffices - a real bug this closes: a method whose own tool can
        // never actually be offered on this channel (e.g. `auth-qr` has no App-frontend UI at
        // all, docs/03-tool-architektur.md) must not still count toward "one method alone
        // already reaches the target" - that silently disabled the two-factor combination
        // fallback (helpsMfa below) for every genuinely offerable method too, stranding an
        // account with only sms+email active (each capped at loa1) the moment it also happened
        // to have an unrelated, unofferable loa2-capable method like `qr` sitting active on the
        // very same account (see the CONFIRM_PEER_LOGIN bug report this fixes).
        val eligible = active.mapNotNull { m ->
            val descriptor = toolRegistry.descriptors()
                .firstOrNull { it.role == MethodRole.IDENTIFIED_AUTH && it.method == m.method }
                ?: return@mapNotNull null
            if (availableTools != null && descriptor.toolId !in availableTools) return@mapNotNull null
            // A key-bound method's AUTH tool must only ever be offered on the exact physical
            // device that holds the matching credential AND while that device is still linked to
            // THIS account (ToolDescriptor.usableByCaller) - a non-extractable device key
            // structurally cannot exist anywhere else, and a device is only ever actively bound to
            // one account at a time, so either mismatch would guarantee failure (docs/04-
            // orchestrierung.md, docs/09-dpop.md).
            if (!descriptor.usableByCaller(m.details, bindingKeyRef, linkedAccountId, account.accountId)) {
                return@mapNotNull null
            }
            m to descriptor
        }

        // Below loa3, MFA isn't a fixed threshold: it's only needed when no single OFFERABLE
        // method's own (capped) level reaches requiredAcr - which is now the normal case for
        // sms/password alone once each is capped at loa1. Once that's true, any remaining
        // active method contributing a factor type not yet proven this session is worth
        // offering, not just when requiresMfa(requiredAcr) says so.
        val singleMethodSuffices = eligible.any { (m, descriptor) ->
            AcrLevel.rank(AcrLevel.min(AcrLevel.of(m.enrolledUnderAcr), descriptor.maxAcr)) >= AcrLevel.rank(requiredAcr)
        }

        return eligible.filter { (m, _) -> m.method !in usedMethods }.mapNotNull { (m, descriptor) ->
            val cappedAcr = AcrLevel.min(AcrLevel.of(m.enrolledUnderAcr), descriptor.maxAcr)
            // What evidence would look like if this candidate were ALSO proven - same shape
            // resolveAcr prices from, no separate catalog re-derivation.
            val projected = AuthEvidence(
                evidence.factors + MethodEvidence(
                    MethodName(m.method), cappedAcr, m.enrolledUnderAcr?.let(AcrLevel::of), descriptor.factorTypes,
                    source = "simulation", amrSourceId = "simulation"
                ),
            )
            val projectedAcr = applyMfaBump(baseAcr(projected.factors), projected)
            val helpsLevel = AcrLevel.rank(projectedAcr) >= AcrLevel.rank(requiredAcr)
            val helpsMfa = !singleMethodSuffices && (descriptor.factorTypes - evidence.factorTypes).isNotEmpty()

            descriptor.toolId.takeIf { helpsLevel || helpsMfa }
        }.distinct() // a multi-instance method can contribute more than one `eligible` entry (several devices) but must only offer its AUTH tool once
    }

    override fun reIdentCandidates(ctx: CandidateContext): List<ToolId> {
        val evidence = ctx.evidence
        val requiredAcr = ctx.requiredAcr
        val usedMethods = evidence.factors.map { it.method.value }.toSet()
        // An identification's amr need not be its method name: ident-nect reports
        // `nect-<procedure>` (the document the user picked at Nect), so "already used" is also
        // read from which tool produced the evidence, not from the method name alone.
        val usedTools = evidence.factors.map { it.amrSourceId }.toSet()
        return toolRegistry.descriptors()
            // Role, not category: `category == IDENT` alone also matches a CORRELATION step,
            // which must never be offered as a way to (re-)identify (its role doc, ADR-18) -
            // typing a semi-public number is no fresh proof even when the account's `requires`
            // claims are already established.
            .filter { it.role == MethodRole.IDENTIFICATION }
            .filter { it.method !in usedMethods && it.toolId.value !in usedTools }
            .filter { AcrLevel.rank(it.maxAcr) >= AcrLevel.rank(requiredAcr) }
            // Same gate as every other candidate path: a tool whose preconditions the account
            // doesn't meet must not be offered here either.
            .filter { descriptor -> descriptor.requires.all { requiresSatisfied(it, ctx.account) } }
            .map { it.toolId }
    }

    /** Highest loa among [factors]' own per-method claims - see [MethodEvidence.loa]. */
    private fun baseAcr(factors: List<MethodEvidence>): AcrLevel {
        val reachable = factors.maxOfOrNull { AcrLevel.rank(it.loa) } ?: return AcrLevel.NONE
        return AcrLevel.levelAt(reachable)
    }

    /**
     * MFA bump: >=2 DISTINCT methods among [evidence], together covering >=2 distinct factor
     * types, earn one tier above [base] - capped by the highest loa any of them was itself
     * enrolled under (docs/06-ablaeufe.md #1, extended; see class doc).
     *
     * Correctness depends on the CALLER having already excluded [EvidenceAxis.IDENTITY] entries
     * from [evidence] - this function itself does not filter by axis. [authenticatorAssuranceLevel]
     * does that filtering before calling this; an identification like `ident-fsc` DOES produce a
     * real `amr` entry (`ToolOutcome.Completed.Identified.amr`) and would otherwise be free to
     * combine with one unrelated auth factor into a false MFA bump - exactly the double-counting this split exists to prevent (see class
     * doc: an identification already prices its own trust into its own loa and is never
     * re-presented at the moment of authentication, so it must not also buy MFA credit).
     *
     * [authCandidates] calls this directly, unfiltered, on its own projected session evidence -
     * deliberately unchanged by the IAL/AAL split, since that simulation only ever projects AUTH
     * candidates onto already-AUTH session evidence in practice.
     *
     * [MethodEvidence.enrolledUnderAcr] is entirely the assembling caller's own claim (docs/05-api.md
     * Abschnitt 3) - this method never reaches into an account's enrollment records
     * itself, for an orchestrator-proven method or a natively-reported one alike. A method with no
     * entry there simply contributes nothing to the cap, never a special case to detect: a bump
     * resting entirely on such methods is capped to "none" by construction, exactly as it already
     * is for an account with no matching enrollment at all.
     */
    private fun applyMfaBump(base: AcrLevel, evidence: AuthEvidence): AcrLevel {
        val distinctMethods = evidence.factors.map { it.method }.distinct().size
        val maxEnrolledUnderAcr = evidence.factors.mapNotNull { it.enrolledUnderAcr }
            .maxOfOrNull { AcrLevel.rank(it) }
            ?.let { AcrLevel.levelAt(it) }
            ?: AcrLevel.NONE
        return combinedAcr(base, distinctMethods, evidence.factorTypes, maxEnrolledUnderAcr)
    }

    /**
     * The MFA-combination rule itself (see class doc): >=2 distinct methods covering >=2 distinct
     * factor types earn one tier above [base] - capped by [maxEnrolledUnderAcr] (the highest loa
     * any of the combining methods was itself enrolled under) AND, independently, by
     * [NIST_COMBINATION_CEILING] - otherwise [base] unchanged. Shared by [canAccountReach] and
     * [applyMfaBump] (over different inputs: an account's full standing methods vs. one session's
     * proven AUTH methods).
     *
     * The [NIST_COMBINATION_CEILING] cap exists because "combine two things, get one tier higher"
     * is only actually NIST-800-63B-conformant for the loa1->loa2 (AAL1->AAL2) step: AAL2 is
     * explicitly defined as reachable via "a combination of two single-factor authenticators".
     * AAL3 has no such combination rule in NIST's model - it requires a SPECIFIC authenticator
     * technology (hardware-based, verifier-impersonation-resistant, e.g. WebAuthn/FIDO2), not "two
     * already-strong things combined". Without this cap, two hypothetical loa2-rated methods of
     * different factor types would bump to loa3 here - a result this project cannot claim is
     * standards-conformant (docs/04-orchestrierung.md #8).
     */
    private fun combinedAcr(base: AcrLevel, distinctMethods: Int, factorTypesUnion: Set<FactorType>, maxEnrolledUnderAcr: AcrLevel): AcrLevel {
        if (distinctMethods < 2 || factorTypesUnion.size < 2) return base
        val bumped = AcrLevel.min(AcrLevels.bump(base), maxEnrolledUnderAcr)
        return AcrLevel.max(base, AcrLevel.min(bumped, NIST_COMBINATION_CEILING))
    }

    private fun descriptorFor(method: String): ToolDescriptor? = toolRegistry.descriptors().firstOrNull { it.method == method }

    private fun requiresMfa(requiredAcr: AcrLevel) = AcrLevel.rank(requiredAcr) >= AcrLevel.rank(MFA_FROM_ACR)

    companion object {
        private val MFA_FROM_ACR = AcrLevel.LOA3

        /** See [combinedAcr]'s doc: the highest level the generic two-factor-combination bump may ever produce. */
        private val NIST_COMBINATION_CEILING = AcrLevel.LOA2
    }
}

/**
 * The only requirement any tool currently declares is EMAIL at PROVEN, whose consolidated
 * value is the account's `emailConfirmed` boolean. Any other requirement (another attribute
 * type, or a trust level above PROVEN) cannot be satisfied and counts as unmet. Shared by
 * [DefaultAuthPolicy.enrollmentCandidates] (offering) and
 * `ToolControllerSupport.validatePreconditions` (direct-activation defense) so the two gates
 * cannot drift apart.
 */
/**
 * Whether [account] already carries what a tool declares it needs: the attribute established at
 * no less than the required [TrustLevel], counted over assertions minus retractions
 * (`AccountProfile.establishedClaims`, ADR-12).
 *
 * Generic on purpose. `ClaimRequirement(EMAIL, PROVEN)` - `enroll-password`'s gate - is now one
 * case of this rule rather than its definition, which is what lets `ident-kvnr` require an
 * attested name/vorname/geburtsdatum without any tool knowing which procedure attested them.
 */
internal fun requiresSatisfied(requirement: ClaimRequirement, account: AccountProfile?): Boolean {
    val established = account?.establishedClaims?.get(requirement.attributeType) ?: return false
    return established.rank >= requirement.minTrustLevel.rank
}
