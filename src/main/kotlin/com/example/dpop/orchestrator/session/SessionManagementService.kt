package com.example.dpop.orchestrator.session

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.Optional
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

    fun getChannelSessionByBindingKeyRef(bindingKeyRef: String): Optional<ChannelSession> =
        channelSessionRepository.findByBindingKeyRef(bindingKeyRef)
            .filter { !it.isExpired }

    fun getOrCreateChannelSession(
        bindingKeyRef: String,
        channel: ChannelSession.Channel,
        ttl: Duration
    ): ChannelSession =
        getChannelSessionByBindingKeyRef(bindingKeyRef)
            .orElseGet { createChannelSession(channel, bindingKeyRef, ttl) }

    fun updateChannelSession(session: ChannelSession) {
        session.touch()
        channelSessionRepository.save(session)
    }

    fun updateChannelState(channelSessionId: UUID, newState: ChannelState) {
        channelSessionRepository.findById(channelSessionId).ifPresent { session ->
            session.state = newState
            session.touch()
            channelSessionRepository.save(session)
        }
    }

    fun setAccountId(channelSessionId: UUID, accountId: Long) {
        channelSessionRepository.findById(channelSessionId).ifPresent { session ->
            session.accountId = accountId
            session.touch()
            channelSessionRepository.save(session)
        }
    }

    fun getChannelSessionWithAuth(channelSessionId: UUID): Optional<ChannelSession> =
        channelSessionRepository.findById(channelSessionId)
            .filter { !it.isExpired }

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

    fun getProcessSessionById(processSessionId: UUID): Optional<ProcessSession> =
        processSessionRepository.findById(processSessionId)
            .filter { !it.isExpired }

    fun getLatestProcessSessionByChannel(channelSessionId: UUID, purpose: ProcessPurpose): Optional<ProcessSession> =
        processSessionRepository.findByChannelSessionIdAndPurpose(channelSessionId, purpose)
            .filter { !it.isExpired }

    fun updateProcessSession(session: ProcessSession) {
        processSessionRepository.save(session)
    }

    fun consumeProcessSession(processSessionId: UUID) {
        getProcessSessionById(processSessionId).ifPresent { session ->
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

    fun getLatestAttemptForProcessSession(processSessionId: UUID): Optional<OrchestratorAttempt> =
        attemptRepository.findLatestByProcessSessionId(processSessionId)
            .filter { !it.isExpired }

    fun updateAttempt(attempt: OrchestratorAttempt) {
        attemptRepository.save(attempt)
    }

    fun getChannelSessionById(channelSessionId: UUID): Optional<ChannelSession> =
        channelSessionRepository.findById(channelSessionId)
            .filter { !it.isExpired }

    fun getAttemptById(attemptId: UUID): Optional<OrchestratorAttempt> =
        attemptRepository.findById(attemptId)
            .filter { !it.isExpired }

    fun completeAttempt(attemptId: UUID, nextContext: String, nextStep: String) {
        attemptRepository.findById(attemptId).ifPresent { attempt ->
            attempt.status = AttemptStatus.VERIFIED
            attempt.nextContext = nextContext
            attempt.nextStep = nextStep
            attemptRepository.save(attempt)
        }
    }
}
