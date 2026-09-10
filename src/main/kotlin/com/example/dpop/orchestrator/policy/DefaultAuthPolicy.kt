package com.example.dpop.orchestrator.policy

import com.example.dpop.account.AccountProfile
import com.example.dpop.orchestrator.session.AcrLevel
import com.example.dpop.orchestrator.session.AcrLevels
import com.example.dpop.orchestrator.tool.ToolHandlerRegistry
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.ToolCategory
import com.example.dpop.tool_spi.ToolDescriptor
import org.springframework.stereotype.Component

/**
 * Provisional default implementation (docs/11-umsetzungsplan.md, Phase B3): the amr->acr
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
 */
@Component
class DefaultAuthPolicy(private val toolRegistry: ToolHandlerRegistry) : AuthPolicy {

    override fun resolveAcr(evidence: AuthEvidence, account: AccountProfile?): String {
        if (evidence.factors.isEmpty()) return "none"
        return applyMfaBump(baseAcr(evidence.factors), evidence).value
    }

    override fun isSatisfied(evidence: AuthEvidence, requiredAcr: String, account: AccountProfile?): Boolean {
        val levelOk = AcrLevels.rank(resolveAcr(evidence, account)) >= AcrLevels.rank(requiredAcr)
        val mfaOk = !requiresMfa(requiredAcr) || evidence.factorTypes.size >= 2
        return levelOk && mfaOk
    }

    override fun canAccountReach(account: AccountProfile, requiredAcr: String): Boolean {
        val active = account.authenticationMethods.filter { it.active }
        if (active.isEmpty()) return false

        val factorTypesUnion = active.flatMap { m -> descriptorFor(m.method)?.factorTypes.orEmpty() }.toSet()
        val bestAcr = active
            .mapNotNull { m -> descriptorFor(m.method)?.let { AcrLevels.min(m.enrolledUnderAcr, it.maxAcr) } }
            .maxByOrNull { AcrLevels.rank(it) }
            ?: "none"
        val distinctMethods = active.map { it.method }.distinct().size
        val maxEnrolledUnderAcr = active.maxOfOrNull { AcrLevels.rank(it.enrolledUnderAcr) }?.let { AcrLevels.levelAt(it) } ?: "none"
        val effectiveAcr = combinedAcr(bestAcr, distinctMethods, factorTypesUnion, maxEnrolledUnderAcr)

        val levelOk = AcrLevels.rank(effectiveAcr) >= AcrLevels.rank(requiredAcr)
        val mfaOk = !requiresMfa(requiredAcr) || factorTypesUnion.size >= 2
        return levelOk && mfaOk
    }

    override fun unreachableReason(account: AccountProfile, requiredAcr: String): String {
        val active = account.authenticationMethods.filter { it.active }
        if (active.isEmpty()) return "Für dieses Konto ist derzeit kein aktives Anmeldeverfahren eingerichtet."

        val descriptors = active.mapNotNull { m -> descriptorFor(m.method)?.let { m to it } }
        val factorTypesUnion = descriptors.flatMap { it.second.factorTypes }.toSet()
        val distinctMethods = descriptors.map { it.second.method }.distinct().size

        if (distinctMethods < 2 || factorTypesUnion.size < 2) {
            val methodNames = descriptors.map { it.second.method }.distinct().joinToString(", ")
            return "Die aktiven Verfahren ($methodNames) decken nur einen Faktor-Typ ab " +
                "(${factorTypesUnion.joinToString(", ") { germanFactorType(it) }}). Für dieses Sicherheitsniveau " +
                "ist zusätzlich ein Verfahren mit einem ANDEREN Faktor-Typ nötig, z. B. ein Passwort (Wissen), " +
                "wenn bisher nur Besitz-Verfahren wie SMS oder E-Mail aktiv sind."
        }

        val maxEnrolledUnderAcr = active.maxOfOrNull { AcrLevels.rank(it.enrolledUnderAcr) }?.let { AcrLevels.levelAt(it) } ?: "none"
        return "Die aktiven Verfahren würden in Kombination reichen, wurden aber unter einem niedrigeren " +
            "Sicherheitsniveau eingerichtet ($maxEnrolledUnderAcr) - das begrenzt, wie hoch sie gemeinsam wirken " +
            "können. Ein neues Verfahren muss erst unter dem höheren Niveau eingerichtet werden."
    }

    private fun germanFactorType(type: FactorType): String = when (type) {
        FactorType.KNOWLEDGE -> "Wissen"
        FactorType.POSSESSION -> "Besitz"
        FactorType.INHERENCE -> "Inhärenz"
    }

    override fun enrollmentCandidates(account: AccountProfile, requiredAcr: String): List<String> {
        val activeMethods = account.authenticationMethods.filter { it.active }.map { it.method }.toSet()
        return toolRegistry.descriptors()
            .filter { it.role.category == ToolCategory.ENROLL }
            // Singleton methods disappear from the offer once active; multi-instance methods
            // (device) keep being offered - a NEW physical device can always add its own instance
            // even though other devices already have theirs (docs/03-tool-architektur.md).
            .filter { it.method !in activeMethods || it.allowsMultipleInstances }
            .filter { !it.requiresConfirmedEmail || account.emailConfirmed }
            .map { it.toolId }
    }

    override fun candidateTools(evidence: AuthEvidence, requiredAcr: String, account: AccountProfile, bindingKeyRef: String?): List<String> {
        val usedMethods = evidence.factors.map { it.method.value }.toSet()
        val active = account.authenticationMethods.filter { it.active }
        val remaining = active.filter { it.method !in usedMethods }

        // Below loa3, MFA isn't a fixed threshold: it's only needed when no single active
        // method's own (capped) level reaches requiredAcr - which is now the normal case for
        // sms/password alone once each is capped at loa1. Once that's true, any remaining
        // active method contributing a factor type not yet proven this session is worth
        // offering, not just when requiresMfa(requiredAcr) says so.
        val singleMethodSuffices = active.any { m ->
            val descriptor = descriptorFor(m.method)
            descriptor != null && AcrLevels.rank(AcrLevels.min(m.enrolledUnderAcr, descriptor.maxAcr)) >= AcrLevels.rank(requiredAcr)
        }

        return remaining.mapNotNull { m ->
            // A session with an already-known account must always be offered the IDENTIFIED_AUTH tool
            // for a method, never a LOOKUP_AUTH sibling that expects to resolve the account itself
            // from a submitted email (docs/03-tool-architektur.md). role (not category) is the
            // key that actually distinguishes the two - category=AUTH alone matches both.
            val descriptor = toolRegistry.descriptors()
                .firstOrNull { it.role == MethodRole.IDENTIFIED_AUTH && it.method == m.method }
                ?: return@mapNotNull null

            // A multi-instance method's AUTH tool must only ever be offered on the exact physical
            // device that holds the matching credential - a non-extractable device key
            // structurally cannot exist anywhere else, so offering it elsewhere would guarantee
            // failure (docs/04-orchestrierung.md). Read straight off THIS already-resolved,
            // unambiguous AUTH descriptor - never re-looked-up by method name alone, which could
            // land on a different tool sharing the same method (docs/03-tool-architektur.md).
            // Delegated to the descriptor itself (ToolDescriptor.matchesCaller) - this generic
            // policy layer never reads a concrete tool's own detail-map key.
            if (descriptor.allowsMultipleInstances && !descriptor.matchesCaller(m.details, bindingKeyRef)) {
                return@mapNotNull null
            }

            val cappedAcr = AcrLevels.min(m.enrolledUnderAcr, descriptor.maxAcr)
            // What evidence would look like if this candidate were ALSO proven - same shape
            // resolveAcr prices from, no separate catalog re-derivation.
            val projected = AuthEvidence(
                evidence.factors + MethodEvidence(
                    MethodName(m.method), AcrLevel(cappedAcr), m.enrolledUnderAcr?.let(::AcrLevel), descriptor.factorTypes,
                    source = "simulation", amrSourceId = "simulation"
                ),
            )
            val projectedAcr = applyMfaBump(baseAcr(projected.factors), projected)
            val helpsLevel = AcrLevels.rank(projectedAcr.value) >= AcrLevels.rank(requiredAcr)
            val helpsMfa = !singleMethodSuffices && (descriptor.factorTypes - evidence.factorTypes).isNotEmpty()

            descriptor.toolId.takeIf { helpsLevel || helpsMfa }
        }.distinct() // a multi-instance method can contribute more than one `remaining` entry (several devices) but must only offer its AUTH tool once
    }

    override fun reIdentCandidates(evidence: AuthEvidence, requiredAcr: String): List<String> {
        val usedMethods = evidence.factors.map { it.method.value }.toSet()
        return toolRegistry.descriptors()
            .filter { it.role.category == ToolCategory.IDENT }
            .filter { it.method !in usedMethods }
            .filter { AcrLevels.rank(it.maxAcr) >= AcrLevels.rank(requiredAcr) }
            .map { it.toolId }
    }

    /** Highest loa among [factors]' own per-method claims - see [MethodEvidence.loa]. */
    private fun baseAcr(factors: List<MethodEvidence>): AcrLevel {
        val reachable = factors.maxOfOrNull { AcrLevels.rank(it.loa.value) } ?: return AcrLevel("none")
        return AcrLevel(AcrLevels.levelAt(reachable))
    }

    /**
     * MFA bump: >=2 DISTINCT methods among [evidence], together covering >=2 distinct factor
     * types, earn one tier above [base] - capped by the highest loa any of them was itself
     * enrolled under (docs/06-ablaeufe.md #1, extended; see class doc). Relies on an existing
     * invariant this whole file already assumes: only a IDENTIFIED_AUTH/LOOKUP_AUTH tool's outcome
     * ever reports a non-empty `amr`/`factorTypes` at all (docs/tool_spi/ToolOutcome.kt) - an
     * identification like ident-fsc, which already prices its own trust into its own loa, never
     * contributes an `amr` entry here to begin with, so it can never be double-counted into this
     * bump just because it ran in the same session as an unrelated auth factor.
     *
     * [MethodEvidence.enrolledUnderAcr] is entirely the assembling caller's own claim (docs/ideen/
     * web-keycloak-kanal.md #8) - this method never reaches into an account's enrollment records
     * itself, for an orchestrator-proven method or a natively-reported one alike. A method with no
     * entry there simply contributes nothing to the cap, never a special case to detect: a bump
     * resting entirely on such methods is capped to "none" by construction, exactly as it already
     * is for an account with no matching enrollment at all.
     */
    private fun applyMfaBump(base: AcrLevel, evidence: AuthEvidence): AcrLevel {
        val distinctMethods = evidence.factors.map { it.method }.distinct().size
        val maxEnrolledUnderAcr = evidence.factors.mapNotNull { it.enrolledUnderAcr?.value }
            .maxOfOrNull { AcrLevels.rank(it) }
            ?.let { AcrLevels.levelAt(it) }
            ?: "none"
        return AcrLevel(combinedAcr(base.value, distinctMethods, evidence.factorTypes, maxEnrolledUnderAcr))
    }

    /**
     * The MFA-combination rule itself (see class doc): >=2 distinct methods covering >=2 distinct
     * factor types earn one tier above [base] - capped by [maxEnrolledUnderAcr], the highest loa
     * any of the combining methods was itself enrolled under - otherwise [base] unchanged. Shared
     * by [canAccountReach] and [applyMfaBump] (over different inputs: an account's full standing
     * methods vs. one session's proven AUTH methods).
     */
    private fun combinedAcr(base: String, distinctMethods: Int, factorTypesUnion: Set<FactorType>, maxEnrolledUnderAcr: String): String {
        if (distinctMethods < 2 || factorTypesUnion.size < 2) return base
        return AcrLevels.max(base, AcrLevels.min(AcrLevels.bump(base), maxEnrolledUnderAcr))
    }

    private fun descriptorFor(method: String): ToolDescriptor? = toolRegistry.descriptors().firstOrNull { it.method == method }

    private fun requiresMfa(requiredAcr: String) = AcrLevels.rank(requiredAcr) >= AcrLevels.rank(MFA_FROM_ACR)

    companion object {
        private const val MFA_FROM_ACR = "loa3"
    }
}
