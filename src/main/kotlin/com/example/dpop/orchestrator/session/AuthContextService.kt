package com.example.dpop.orchestrator.session

import com.example.dpop.tool_spi.FactorType
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class AuthContextService(private val authContextRepository: AuthContextRepository) {

    fun createForAccount(accountId: Long): AuthContext =
        authContextRepository.save(AuthContext(accountId = accountId))

    fun getAuthContext(authContextId: UUID): AuthContext? =
        authContextRepository.findByIdOrNull(authContextId)

    /** Records what a completed tool proved into this session's evidence (docs/04-orchestrierung.md #1). */
    fun applyEvidence(authContextId: UUID, amr: List<String>, factorTypes: Set<FactorType>, effectiveAcr: String?) {
        val authContext = authContextRepository.findByIdOrNull(authContextId)
            ?: throw IllegalArgumentException("AuthContext not found: $authContextId")
        authContext.addAmr(amr)
        authContext.addFactorTypes(factorTypes)
        effectiveAcr?.let { authContext.currentAcr = it }
        authContextRepository.save(authContext)
    }
}
