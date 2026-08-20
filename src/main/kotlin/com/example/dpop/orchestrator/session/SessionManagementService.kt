package com.example.dpop.orchestrator.session

import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
@Transactional
class SessionManagementService(
    private val channelSessionRepository: ChannelSessionRepository,
    private val processSessionRepository: ProcessSessionRepository,
    private val attemptRepository: OrchestratorAttemptRepository
) {

    // ChannelSession management

    fun createChannelSession(channel: ChannelSession.Channel, bindingKeyRef: String, ttl: Duration): ChannelSession {
        val expiresAt = Instant.now().plus(ttl)
        val session = ChannelSession(channel, bindingKeyRef, expiresAt)
        return channelSessionRepository.save(session)
    }

    fun findChannelSessionByBindingKeyRef(bindingKeyRef: String): ChannelSession? =
        channelSessionRepository.findByBindingKeyRef(bindingKeyRef)
            ?.takeIf { !it.isExpired }

    fun getOrCreateChannelSession(
        bindingKeyRef: String,
        channel: ChannelSession.Channel,
        ttl: Duration
    ): ChannelSession =
        findChannelSessionByBindingKeyRef(bindingKeyRef)
            ?: createChannelSession(channel, bindingKeyRef, ttl)

    fun updateChannelSession(session: ChannelSession) {
        session.touch()
        channelSessionRepository.save(session)
    }

    fun updateChannelState(channelSessionId: UUID, newState: ChannelState) {
        channelSessionRepository.findByIdOrNull(channelSessionId)?.let { session ->
            session.state = newState
            session.touch()
            channelSessionRepository.save(session)
        }
    }

    fun bindAccountId(channelSessionId: UUID, accountId: Long) {
        channelSessionRepository.findByIdOrNull(channelSessionId)?.let { session ->
            session.accountId = accountId
            session.touch()
            channelSessionRepository.save(session)
        }
    }

    fun findChannelSessionWithAuth(channelSessionId: UUID): ChannelSession? =
        channelSessionRepository.findByIdOrNull(channelSessionId)
            ?.takeIf { !it.isExpired }

    // ProcessSession management

    fun createRegistrationProcessSession(channelSessionId: UUID, ttl: Duration): RegistrationProcessSession {
        val expiresAt = Instant.now().plus(ttl)
        val session = RegistrationProcessSession(channelSessionId, expiresAt)
        return processSessionRepository.save(session)
    }

    fun createLoginProcessSession(channelSessionId: UUID, ttl: Duration): LoginProcessSession {
        val expiresAt = Instant.now().plus(ttl)
        val session = LoginProcessSession(channelSessionId, expiresAt)
        return processSessionRepository.save(session)
    }

    fun createStepUpProcessSession(channelSessionId: UUID, requiredAcr: String, ttl: Duration): StepUpProcessSession {
        val expiresAt = Instant.now().plus(ttl)
        val session = StepUpProcessSession(channelSessionId, requiredAcr, expiresAt)
        return processSessionRepository.save(session)
    }

    fun findProcessSessionById(processSessionId: UUID): ProcessSession? =
        processSessionRepository.findByIdOrNull(processSessionId)
            ?.takeIf { !it.isExpired }

    fun findLatestProcessSessionByChannel(channelSessionId: UUID, purpose: ProcessPurpose): ProcessSession? =
        processSessionRepository.findByChannelSessionIdAndPurpose(channelSessionId, purpose)
            ?.takeIf { !it.isExpired }

    fun updateProcessSession(session: ProcessSession) {
        processSessionRepository.save(session)
    }

    fun consumeProcessSession(processSessionId: UUID) {
        findProcessSessionById(processSessionId)?.let { session ->
            session.consume()
            updateProcessSession(session)
        }
    }

    // OrchestratorAttempt management

    fun createIdentificationAttempt(
        processSessionId: UUID,
        nextContext: String,
        nextStep: String,
        ttl: Duration
    ): IdentificationAttempt {
        val expiresAt = Instant.now().plus(ttl)
        val attempt = IdentificationAttempt(processSessionId, nextContext, nextStep, expiresAt)
        return attemptRepository.save(attempt)
    }

    fun createAuthenticationAttempt(
        processSessionId: UUID,
        nextContext: String,
        nextStep: String,
        ttl: Duration
    ): AuthenticationAttempt {
        val expiresAt = Instant.now().plus(ttl)
        val attempt = AuthenticationAttempt(processSessionId, nextContext, nextStep, expiresAt)
        return attemptRepository.save(attempt)
    }

    fun findLatestAttemptForProcessSession(processSessionId: UUID): OrchestratorAttempt? =
        attemptRepository.findLatestByProcessSessionId(processSessionId)
            ?.takeIf { !it.isExpired }

    fun updateAttempt(attempt: OrchestratorAttempt) {
        attemptRepository.save(attempt)
    }

    fun findChannelSessionById(channelSessionId: UUID): ChannelSession? =
        channelSessionRepository.findByIdOrNull(channelSessionId)
            ?.takeIf { !it.isExpired }

    fun findAttemptById(attemptId: UUID): OrchestratorAttempt? =
        attemptRepository.findByIdOrNull(attemptId)
            ?.takeIf { !it.isExpired }

    fun completeAttempt(attemptId: UUID, nextContext: String, nextStep: String) {
        attemptRepository.findByIdOrNull(attemptId)?.let { attempt ->
            attempt.status = AttemptStatus.VERIFIED
            attempt.nextContext = nextContext
            attempt.nextStep = nextStep
            attemptRepository.save(attempt)
        }
    }
}
