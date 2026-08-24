package com.example.dpop.orchestrator.session

import com.example.dpop.account.AccountService
import com.example.dpop.orchestrator.policy.AuthEvidence
import com.example.dpop.orchestrator.policy.AuthPolicy
import com.example.dpop.tool_spi.FactorType
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class AuthContextService(
    private val authContextRepository: AuthContextRepository,
    private val accountService: AccountService,
    private val authPolicy: AuthPolicy
) {

    fun createForAccount(accountId: Long): AuthContext =
        authContextRepository.save(AuthContext(accountId = accountId))

    fun getAuthContext(authContextId: UUID): AuthContext? =
        authContextRepository.findByIdOrNull(authContextId)

    /**
     * Records what a completed tool proved into this session's evidence (docs/04-orchestrierung.md
     * #1). [effectiveAcr] is this one attempt's own (possibly capped) contribution; the stored
     * `currentAcr` is the larger of that and what the policy resolves from the now-accumulated
     * evidence as a whole - needed so combining two lower-level methods (e.g. sms+password, both
     * loa1) is reflected as their MFA-bumped level, not just the last tool's own number.
     */
    fun applyEvidence(authContextId: UUID, amr: List<String>, factorTypes: Set<FactorType>, effectiveAcr: String?) {
        val authContext = authContextRepository.findByIdOrNull(authContextId)
            ?: throw IllegalArgumentException("AuthContext not found: $authContextId")
        authContext.addAmr(amr)
        authContext.addFactorTypes(factorTypes)
        val account = authContext.accountId?.let { accountService.findAccount(it) }
        val combinedAcr = authPolicy.resolveAcr(AuthEvidence(authContext.currentAmr, authContext.currentFactorTypes), account)
        authContext.currentAcr = AcrLevels.max(effectiveAcr, combinedAcr)
        authContextRepository.save(authContext)
    }
}
