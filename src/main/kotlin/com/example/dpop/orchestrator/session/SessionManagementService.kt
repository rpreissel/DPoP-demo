package com.example.dpop.orchestrator.session

import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
@Transactional
class SessionManagementService(
    private val channelSessionRepository: ChannelSessionRepository,
    private val processSessionRepository: ProcessSessionRepository,
    private val toolSessionRepository: ToolSessionRepository,
    private val sessionEventRepository: SessionEventRepository
) {

    // ChannelSession management ------------------------------------------------

    fun findChannelSessionByBindingKeyRef(bindingKeyRef: String): ChannelSession? =
        channelSessionRepository.findByBindingKeyRef(bindingKeyRef)
            ?.takeIf { !it.isExpired }

    fun getOrCreateChannelSession(
        bindingKeyRef: String,
        channel: ChannelSession.Channel,
        ttl: Duration
    ): ChannelSession =
        findChannelSessionByBindingKeyRef(bindingKeyRef)
            ?: channelSessionRepository.save(ChannelSession(channel, bindingKeyRef, Instant.now().plus(ttl)))

    fun findChannelSessionById(channelSessionId: UUID): ChannelSession? =
        channelSessionRepository.findByIdOrNull(channelSessionId)
            ?.takeIf { !it.isExpired }

    fun updateChannelSession(session: ChannelSession): ChannelSession {
        session.touch()
        return channelSessionRepository.save(session)
    }

    fun updateChannelState(channelSessionId: UUID, newState: ChannelState) {
        channelSessionRepository.findByIdOrNull(channelSessionId)?.let { session ->
            session.state = newState
            session.touch()
            channelSessionRepository.save(session)
        }
    }

    /**
     * requiredAcr is always a lower bound: only raises, never lowers (docs/05-api.md #9).
     * Compared against the EFFECTIVE floor (default applied), not the raw nullable column -
     * otherwise an explicit "loa1" would silently undercut the implicit loa2 baseline.
     */
    fun raiseChannelRequiredAcr(channelSessionId: UUID, requiredAcr: String) {
        channelSessionRepository.findByIdOrNull(channelSessionId)?.let { session ->
            val effectiveFloor = session.requiredAcr ?: AcrLevels.DEFAULT_REQUIRED_ACR
            session.requiredAcr = AcrLevels.max(effectiveFloor, requiredAcr)
            session.touch()
            channelSessionRepository.save(session)
        }
    }

    fun bindAccountAndAuthContext(channelSessionId: UUID, accountId: Long, authContextId: UUID) {
        channelSessionRepository.findByIdOrNull(channelSessionId)?.let { session ->
            session.accountId = accountId
            session.authContextId = authContextId
            session.touch()
            channelSessionRepository.save(session)
        }
    }

    // ProcessSession management -------------------------------------------------

    fun createRegistrationProcessSession(channelSessionId: UUID, ttl: Duration): RegistrationProcessSession =
        processSessionRepository.save(RegistrationProcessSession(channelSessionId, Instant.now().plus(ttl)))

    fun createLoginProcessSession(channelSessionId: UUID, ttl: Duration): LoginProcessSession =
        processSessionRepository.save(LoginProcessSession(channelSessionId, Instant.now().plus(ttl)))

    fun createStepUpProcessSession(channelSessionId: UUID, requiredAcr: String, ttl: Duration): StepUpProcessSession =
        processSessionRepository.save(StepUpProcessSession(channelSessionId, requiredAcr, Instant.now().plus(ttl)))

    fun findProcessSessionById(processSessionId: UUID): ProcessSession? =
        processSessionRepository.findByIdOrNull(processSessionId)
            ?.takeIf { !it.isExpired }

    fun findActiveProcessSession(channelSessionId: UUID): ProcessSession? =
        processSessionRepository.findFirstByChannelSessionIdAndStateOrderByCreatedAtDesc(
            channelSessionId, ProcessState.STARTED
        )?.takeIf { !it.isExpired }

    fun updateProcessSession(session: ProcessSession): ProcessSession =
        processSessionRepository.save(session)

    fun consumeProcessSession(processSessionId: UUID) {
        processSessionRepository.findByIdOrNull(processSessionId)?.let { session ->
            session.consume()
            processSessionRepository.save(session)
        }
    }

    fun failProcessSession(processSessionId: UUID) {
        processSessionRepository.findByIdOrNull(processSessionId)?.let { session ->
            session.fail()
            processSessionRepository.save(session)
        }
    }

    // ToolSession management -----------------------------------------------------

    fun createToolSession(processSessionId: UUID, ttl: Duration): ToolSession =
        toolSessionRepository.save(ToolSession(processSessionId, Instant.now().plus(ttl)))

    fun findToolSessionById(toolSessionId: UUID): ToolSession? =
        toolSessionRepository.findByIdOrNull(toolSessionId)
            ?.takeIf { !it.isExpired }

    fun registerFailedToolAttempt(toolSessionId: UUID): ToolSession {
        val session = toolSessionRepository.findByIdOrNull(toolSessionId)
            ?: throw IllegalArgumentException("Tool session not found: $toolSessionId")
        session.registerFailedAttempt()
        return toolSessionRepository.save(session)
    }

    /**
     * Invalidates an abandoned tool session immediately (Back/Switch) rather than waiting for
     * its TTL. Needed because a re-offered candidate can be the SAME toolId as the one being
     * abandoned (today's catalog only has one method per category) - matching toolId alone
     * wouldn't tell the old and the freshly re-activated tool session apart.
     */
    fun expireToolSession(toolSessionId: UUID) {
        toolSessionRepository.findByIdOrNull(toolSessionId)?.let { session ->
            session.expiresAt = Instant.now()
            toolSessionRepository.save(session)
        }
    }

    // Audit ------------------------------------------------------------------

    fun recordEvent(
        channelSessionId: UUID?,
        processSessionId: UUID?,
        eventType: String,
        source: String,
        payload: Any? = null
    ) {
        val payloadHash = payload?.let { hashPayload(it.toString()) }
        sessionEventRepository.save(SessionEvent(channelSessionId, processSessionId, eventType, source, payloadHash))
    }

    private fun hashPayload(payload: String): String =
        MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
