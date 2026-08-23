package com.example.dpop.orchestrator.api.v1

import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.session.SessionManagementService
import org.springframework.stereotype.Component
import java.util.UUID

/** Binding check shared by the channel and tool endpoints (docs/09-dpop.md #3). */
@Component
class ChannelAccessGuard(private val sessionManagementService: SessionManagementService) {

    fun requireChannel(channelSessionId: UUID, bindingKeyRef: String): ChannelSession {
        val channel = sessionManagementService.findChannelSessionById(channelSessionId)
            ?: throw OrchestratorException.notFound("Channel session not found: $channelSessionId")
        if (channel.bindingKeyRef != bindingKeyRef) {
            throw OrchestratorException.bindingMismatch("DPoP key does not match this channel")
        }
        return channel
    }
}
