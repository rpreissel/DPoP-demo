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
    private val toolSessionRepository: ToolSessionRepository,
    private val sessionEventRepository: SessionEventRepository,
    private val deviceAccountLinkRepository: DeviceAccountLinkRepository
) {

    // ChannelSession management ------------------------------------------------

    /**
     * Always creates a brand-new ChannelSession - never looks one up by [bindingKeyRef]. DPoP
     * only proves which device is talking; it is deliberately NOT a lookup key for resuming a
     * session (that requires the client to present a known channelSessionId via
     * [findChannelSessionById]). [accountId] pre-links the new channel to a known device
     * (see [findLinkedAccountId]) so a returning device can skip straight to LOGIN.
     */
    fun createChannelSession(
        bindingKeyRef: String,
        channel: ChannelSession.Channel,
        ttl: Duration,
        accountId: Long?
    ): ChannelSession {
        val session = ChannelSession(channel, bindingKeyRef, Instant.now().plus(ttl))
        session.accountId = accountId
        return channelSessionRepository.save(session)
    }

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
    fun raiseChannelAcrFloor(channelSessionId: UUID, requiredAcr: String) {
        channelSessionRepository.findByIdOrNull(channelSessionId)?.let { session ->
            val effectiveFloor = session.acrFloor ?: AcrLevels.DEFAULT_REQUIRED_ACR
            session.acrFloor = AcrLevels.max(effectiveFloor, requiredAcr)
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

    // Device-account link management --------------------------------------------

    /** Known account for this device, if any - lets a brand-new ChannelSession skip straight to LOGIN. */
    fun findLinkedAccountId(bindingKeyRef: String): Long? =
        deviceAccountLinkRepository.findByIdOrNull(bindingKeyRef)?.accountId

    /** Idempotent: called every time a channel reaches AUTHENTICATED, regardless of intent. */
    fun linkDeviceToAccount(bindingKeyRef: String, accountId: Long) {
        val existing = deviceAccountLinkRepository.findByIdOrNull(bindingKeyRef)
        if (existing == null) {
            deviceAccountLinkRepository.save(DeviceAccountLink(bindingKeyRef, accountId))
        } else if (existing.accountId != accountId) {
            existing.accountId = accountId
            existing.updatedAt = Instant.now()
            deviceAccountLinkRepository.save(existing)
        }
    }

    // ToolSession management -----------------------------------------------------

    fun createToolSession(journeyId: UUID, ttl: Duration): ToolSession =
        toolSessionRepository.save(ToolSession(journeyId, Instant.now().plus(ttl)))

    fun findToolSessionById(toolSessionId: UUID): ToolSession? =
        toolSessionRepository.findByIdOrNull(toolSessionId)
            ?.takeIf { !it.isExpired }

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
        journeyId: UUID?,
        eventType: String,
        source: String,
        payload: Any? = null
    ) {
        val payloadHash = payload?.let { hashPayload(it.toString()) }
        sessionEventRepository.save(SessionEvent(channelSessionId, journeyId, eventType, source, payloadHash))
    }

    private fun hashPayload(payload: String): String =
        MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
