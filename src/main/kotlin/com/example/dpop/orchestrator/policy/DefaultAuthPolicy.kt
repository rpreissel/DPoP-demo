package com.example.dpop.orchestrator.policy

import com.example.dpop.account.AccountProfile
import com.example.dpop.orchestrator.session.AcrLevels
import com.example.dpop.orchestrator.tool.ToolHandlerRegistry
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
        if (evidence.amr.isEmpty()) return "none"
        return applyMfaBump(baseAcr(evidence.amr), evidence.amr, account)
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
        val combinable = distinctMethods >= 2 && factorTypesUnion.size >= 2
        val maxEnrolledUnderAcr = active.maxOfOrNull { AcrLevels.rank(it.enrolledUnderAcr) }?.let { AcrLevels.levelAt(it) } ?: "none"
        val effectiveAcr = if (combinable) {
            AcrLevels.max(bestAcr, AcrLevels.min(AcrLevels.bump(bestAcr), maxEnrolledUnderAcr))
        } else {
            bestAcr
        }

        val levelOk = AcrLevels.rank(effectiveAcr) >= AcrLevels.rank(requiredAcr)
        val mfaOk = !requiresMfa(requiredAcr) || factorTypesUnion.size >= 2
        return levelOk && mfaOk
    }

    override fun enrollmentCandidates(account: AccountProfile, requiredAcr: String): List<String> {
        val activeMethods = account.authenticationMethods.filter { it.active }.map { it.method }.toSet()
        return toolRegistry.descriptors()
            .filter { it.category == ToolCategory.ENROLL }
            // Singleton methods disappear from the offer once active; multi-instance methods
            // (device) keep being offered - a NEW physical device can always add its own instance
            // even though other devices already have theirs (docs/03-tool-architektur.md).
            .filter { it.method !in activeMethods || it.allowsMultipleInstances }
            .filter { !it.requiresConfirmedEmail || account.emailConfirmed }
            .map { it.toolId }
    }

    override fun candidateTools(evidence: AuthEvidence, requiredAcr: String, account: AccountProfile, bindingKeyRef: String): List<String> {
        val usedMethods = evidence.amr.toSet()
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
            // A session with an already-known account must always be offered the DEVICE_AUTH tool
            // for a method, never a LOOKUP_AUTH sibling that expects to resolve the account itself
            // from a submitted email (docs/03-tool-architektur.md). role (not category) is the
            // key that actually distinguishes the two - category=AUTH alone matches both.
            val descriptor = toolRegistry.descriptors()
                .firstOrNull { it.role == MethodRole.DEVICE_AUTH && it.method == m.method }
                ?: return@mapNotNull null

            // A multi-instance method's AUTH tool must only ever be offered on the exact physical
            // device that holds the matching credential - a non-extractable device key
            // structurally cannot exist anywhere else, so offering it elsewhere would guarantee
            // failure (docs/04-orchestrierung.md). Read straight off THIS already-resolved,
            // unambiguous AUTH descriptor - never re-looked-up by method name alone, which could
            // land on a different tool sharing the same method (docs/03-tool-architektur.md).
            if (descriptor.allowsMultipleInstances && m.details?.get("deviceBindingKeyRef") != bindingKeyRef) {
                return@mapNotNull null
            }

            val cappedAcr = AcrLevels.min(m.enrolledUnderAcr, descriptor.maxAcr)
            val projectedAmr = evidence.amr + m.method
            val projectedBase = AcrLevels.max(baseAcr(projectedAmr), cappedAcr)
            val projectedAcr = applyMfaBump(projectedBase, projectedAmr, account)
            val helpsLevel = AcrLevels.rank(projectedAcr) >= AcrLevels.rank(requiredAcr)
            val helpsMfa = !singleMethodSuffices && (descriptor.factorTypes - evidence.factorTypes).isNotEmpty()

            descriptor.toolId.takeIf { helpsLevel || helpsMfa }
        }.distinct() // a multi-instance method can contribute more than one `remaining` entry (several devices) but must only offer its AUTH tool once
    }

    override fun reIdentCandidates(evidence: AuthEvidence, requiredAcr: String): List<String> {
        val usedMethods = evidence.amr.toSet()
        return toolRegistry.descriptors()
            .filter { it.category == ToolCategory.IDENT }
            .filter { it.method !in usedMethods }
            .filter { AcrLevels.rank(it.maxAcr) >= AcrLevels.rank(requiredAcr) }
            .map { it.toolId }
    }

    /** Highest maxAcr among ALL catalog descriptors (any category) matching one of [methods]. */
    private fun baseAcr(methods: Collection<String>): String {
        val reachable = toolRegistry.descriptors()
            .filter { it.method in methods }
            .maxOfOrNull { AcrLevels.rank(it.maxAcr) }
            ?: return "none"
        return AcrLevels.levelAt(reachable)
    }

    /**
     * MFA bump: >=2 DISTINCT AUTH methods among [methods], together covering >=2 distinct
     * factor types, earn one tier above what those AUTH methods alone could reach - capped by
     * the highest loa any of them was itself enrolled under (docs/06-ablaeufe.md #1, extended;
     * see class doc). The bump is computed on the AUTH-only base and combined into [base] via
     * max - never applied to [base] directly - so an identification like ident-fsc, which
     * already prices its own trust into its maxAcr, is never double-counted just because an
     * unrelated auth factor also ran this session (e.g. fsc=loa2 + sms=loa1 + password=loa1
     * must stay loa2, not overshoot to loa3 merely because sms+password happen to also combine).
     */
    private fun applyMfaBump(base: String, methods: Collection<String>, account: AccountProfile?): String {
        val authDescriptors = toolRegistry.descriptors().filter { it.category == ToolCategory.AUTH && it.method in methods }
        val authBase = authDescriptors.maxOfOrNull { AcrLevels.rank(it.maxAcr) }?.let { AcrLevels.levelAt(it) } ?: "none"
        val distinctAuthMethods = authDescriptors.map { it.method }.distinct().size
        val authFactorTypes = authDescriptors.flatMap { it.factorTypes }.toSet()
        val combinable = distinctAuthMethods >= 2 && authFactorTypes.size >= 2
        if (!combinable) return AcrLevels.max(base, authBase)

        val authMethodNames = authDescriptors.map { it.method }.toSet()
        val maxEnrolledUnderAcr = account?.authenticationMethods
            ?.filter { it.active && it.method in authMethodNames }
            ?.maxOfOrNull { AcrLevels.rank(it.enrolledUnderAcr) }
            ?.let { AcrLevels.levelAt(it) }
            ?: "none"
        val bumpedAuthBase = AcrLevels.max(authBase, AcrLevels.min(AcrLevels.bump(authBase), maxEnrolledUnderAcr))
        return AcrLevels.max(base, bumpedAuthBase)
    }

    private fun descriptorFor(method: String): ToolDescriptor? = toolRegistry.descriptors().firstOrNull { it.method == method }

    private fun requiresMfa(requiredAcr: String) = AcrLevels.rank(requiredAcr) >= AcrLevels.rank(MFA_FROM_ACR)

    companion object {
        private const val MFA_FROM_ACR = "loa3"
    }
}
