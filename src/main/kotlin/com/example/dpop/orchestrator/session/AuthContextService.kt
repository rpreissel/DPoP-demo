package com.example.dpop.orchestrator.session

import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
@Transactional
class AuthContextService(private val authContextRepository: AuthContextRepository) {

    fun createAuthContext(accountId: Long, keycloakSessionId: String, keycloakSubject: String): AuthContext {
        val authContext = AuthContext(accountId, keycloakSessionId, keycloakSubject)
        return authContextRepository.save(authContext)
    }

    fun updateAcr(authContextId: UUID, acr: String, amr: String): AuthContext {
        val authContext = authContextRepository.findByIdOrNull(authContextId)
            ?: throw IllegalArgumentException("AuthContext not found")

        authContext.currentAcr = acr
        authContext.currentAmr = amr
        authContext.updatedAt = Instant.now()

        return authContextRepository.save(authContext)
    }

    fun getAuthContext(authContextId: UUID): AuthContext? =
        authContextRepository.findByIdOrNull(authContextId)
}
