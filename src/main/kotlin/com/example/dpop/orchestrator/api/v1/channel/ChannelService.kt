package com.example.dpop.orchestrator.api.v1.channel

import com.example.dpop.orchestrator.api.v1.ChannelSessionResponse
import com.example.dpop.orchestrator.api.v1.OrchestratorResponse
import com.example.dpop.orchestrator.session.AttemptStatus
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.orchestrator.session.SessionManagementService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.util.UUID

@Service
@Transactional
class ChannelService(private val sessionManagementService: SessionManagementService) {

    fun initializeFlow(bindingKeyRef: String, channel: ChannelSession.Channel): OrchestratorResponse {
        val channelSession = sessionManagementService.getOrCreateChannelSession(
            bindingKeyRef = bindingKeyRef,
            channel = channel,
            ttl = Duration.ofMinutes(30)
        )
        return initializeFlow(channelSession)
    }

    fun initializeFlow(channelSession: ChannelSession): OrchestratorResponse {
        sessionManagementService.updateChannelSession(channelSession)

        val channelId = channelSession.channelSessionId
            ?: throw IllegalStateException("Channel session has no id")

        if (channelSession.accountId != null) {
            sessionManagementService.updateChannelState(channelId, ChannelState.AUTHENTICATED)
            return OrchestratorResponse(
                channelSessionId = channelId,
                next = OrchestratorResponse.NextRouting(
                    context = "authentication",
                    step = "selectMethod",
                    methods = listOf("sms")
                )
            )
        } else {
            sessionManagementService.updateChannelState(channelId, ChannelState.REGISTERING)
            val processSession = sessionManagementService.createRegistrationProcessSession(
                channelId,
                Duration.ofMinutes(15)
            )
            val processSessionId = processSession.processSessionId
                ?: throw IllegalStateException("Process session has no id")

            val attempt = sessionManagementService.createIdentificationAttempt(
                processSessionId,
                "fsc",
                "input",
                Duration.ofMinutes(10)
            )
            val attemptId = attempt.attemptId ?: throw IllegalStateException("Attempt has no id")
            attempt.status = AttemptStatus.INPUT_REQUIRED
            attempt.missingFields = serializeList(listOf("kvnr", "fsc"))
            sessionManagementService.updateAttempt(attempt)

            return OrchestratorResponse(
                channelSessionId = channelId,
                processState = OrchestratorResponse.ProcessState(
                    purpose = "REGISTRATION",
                    status = "ACTIVE",
                    personId = null,
                    accountId = null
                ),
                attemptState = OrchestratorResponse.AttemptState(
                    attemptId = attemptId,
                    attemptType = "identification",
                    status = "INPUT_REQUIRED",
                    missingFields = listOf("kvnr", "name"),
                    result = null
                ),
                next = OrchestratorResponse.NextRouting(
                    context = "fsc",
                    step = "input"
                )
            )
        }
    }

    fun findChannelSession(channelSessionId: UUID): ChannelSessionResponse {
        val channelSession = sessionManagementService.findChannelSessionById(channelSessionId)
            ?: throw IllegalArgumentException("Channel session not found")

        var currentAmr: List<String>? = null
        var currentAcr: String? = null
        if (channelSession.authContextId != null) {
            // TODO: Load AuthContext and extract ACR/AMR
        }

        return ChannelSessionResponse(
            channelSessionId = channelSession.channelSessionId,
            state = channelSession.state,
            currentAcr = currentAcr,
            currentAmr = currentAmr,
            stepUpRequired = channelSession.state == ChannelState.STEP_UP_REQUIRED,
            accountId = channelSession.accountId
        )
    }

    private fun serializeList(list: List<String>): String =
        list.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
}
