package com.example.dpop.orchestrator.policy

import com.example.dpop.account.AccountProfile
import com.example.dpop.orchestrator.session.AcrLevels
import com.example.dpop.orchestrator.tool.ToolHandlerRegistry
import com.example.dpop.tool_spi.ToolCategory
import org.springframework.stereotype.Component

/**
 * Provisional default implementation (docs/11-umsetzungsplan.md, Phase B3): the amr->acr
 * mapping is fachlich/regulatorisch offen (docs/04-orchestrierung.md #2) and only stubbed
 * here via each tool's own maxAcr. MFA is required from loa3 upward.
 */
@Component
class DefaultAuthPolicy(private val toolRegistry: ToolHandlerRegistry) : AuthPolicy {

    override fun resolveAcr(evidence: AuthEvidence): String {
        if (evidence.amr.isEmpty()) return "none"
        val reachable = toolRegistry.descriptors()
            .filter { it.method in evidence.amr }
            .maxOfOrNull { AcrLevels.rank(it.maxAcr) }
            ?: return "none"
        return AcrLevels.max("none", levelAt(reachable))
    }

    override fun isSatisfied(evidence: AuthEvidence, requiredAcr: String): Boolean {
        val levelOk = AcrLevels.rank(resolveAcr(evidence)) >= AcrLevels.rank(requiredAcr)
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

        val levelOk = AcrLevels.rank(bestAcr) >= AcrLevels.rank(requiredAcr)
        val mfaOk = !requiresMfa(requiredAcr) || factorTypesUnion.size >= 2
        return levelOk && mfaOk
    }

    override fun enrollmentCandidates(account: AccountProfile, requiredAcr: String): List<String> {
        val activeMethods = account.authenticationMethods.filter { it.active }.map { it.method }.toSet()
        return toolRegistry.descriptors()
            .filter { it.category == ToolCategory.ENROLL }
            .filter { it.method !in activeMethods }
            .map { it.toolId }
    }

    override fun candidateTools(evidence: AuthEvidence, requiredAcr: String, account: AccountProfile): List<String> {
        val usedMethods = evidence.amr.toSet()
        val active = account.authenticationMethods.filter { it.active && it.method !in usedMethods }

        return active.mapNotNull { m ->
            val descriptor = toolRegistry.descriptors()
                .firstOrNull { it.category == ToolCategory.AUTH && it.method == m.method }
                ?: return@mapNotNull null

            val cappedAcr = AcrLevels.min(m.enrolledUnderAcr, descriptor.maxAcr)
            val helpsLevel = AcrLevels.rank(cappedAcr) >= AcrLevels.rank(requiredAcr)
            val helpsMfa = requiresMfa(requiredAcr) && (descriptor.factorTypes - evidence.factorTypes).isNotEmpty()

            descriptor.toolId.takeIf { helpsLevel || helpsMfa }
        }
    }

    private fun descriptorFor(method: String) = toolRegistry.descriptors().firstOrNull { it.method == method }

    private fun requiresMfa(requiredAcr: String) = AcrLevels.rank(requiredAcr) >= AcrLevels.rank(MFA_FROM_ACR)

    private fun levelAt(rank: Int): String = LEVELS_BY_RANK.getOrElse(rank) { "none" }

    companion object {
        private val LEVELS_BY_RANK = listOf("none", "loa1", "loa2", "loa3")
        private const val MFA_FROM_ACR = "loa3"
    }
}
