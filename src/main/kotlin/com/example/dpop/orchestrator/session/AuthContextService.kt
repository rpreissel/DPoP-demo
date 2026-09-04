package com.example.dpop.orchestrator.session

import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** APP-channel-only token bookkeeping - see [AuthContext]'s own doc for why this stayed small. */
@Service
@Transactional
class AuthContextService(
    private val authContextRepository: AuthContextRepository
) {

    fun createForAccount(accountId: Long, authEvidenceId: UUID): AuthContext =
        authContextRepository.save(AuthContext(accountId = accountId).apply { this.authEvidenceId = authEvidenceId })

    fun getAuthContext(authContextId: UUID): AuthContext? =
        authContextRepository.findByIdOrNull(authContextId)
}
