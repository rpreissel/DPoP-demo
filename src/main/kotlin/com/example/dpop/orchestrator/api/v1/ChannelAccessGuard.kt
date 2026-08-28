package com.example.dpop.orchestrator.api.v1

import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.session.SessionManagementService
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.UUID

/** Binding check shared by the channel and tool endpoints (docs/09-dpop.md #3). */
@Component
class ChannelAccessGuard(private val sessionManagementService: SessionManagementService) {

    fun requireChannel(channelSessionId: UUID, bindingKeyRef: String): ChannelSession {
        val channel = sessionManagementService.findChannelSessionById(channelSessionId)
            ?: throw OrchestratorException.notFound("Channel session not found: $channelSessionId")
        // Constant-time, though both sides are public thumbprints rather than secrets - the
        // cheapest way to keep this from becoming one if the binding ever carries more.
        if (!MessageDigest.isEqual(channel.bindingKeyRef?.toByteArray(), bindingKeyRef.toByteArray())) {
            throw OrchestratorException.bindingMismatch("DPoP key does not match this channel")
        }
        return channel
    }
}
