package com.example.dpop.orchestrator.api.v1.channel

import com.example.dpop.account.AccountService
import com.example.dpop.orchestrator.api.v1.ChannelAccessGuard
import com.example.dpop.orchestrator.api.v1.OrchestratorException
import com.example.dpop.orchestrator.orchestration.CandidateOffering
import com.example.dpop.orchestrator.orchestration.Next
import com.example.dpop.orchestrator.orchestration.ProcessCancellationService
import com.example.dpop.orchestrator.policy.AuthEvidence
import com.example.dpop.orchestrator.policy.AuthPolicy
import com.example.dpop.orchestrator.session.AcrLevels
import com.example.dpop.orchestrator.session.AuthContextService
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.orchestrator.session.ProcessSession
import com.example.dpop.orchestrator.session.SessionManagementService
import com.example.dpop.orchestrator.tool.ToolHandlerRegistry
import com.example.dpop.tool_spi.ToolCategory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.util.UUID

@Service
@Transactional
class ChannelService(
    private val sessionManagementService: SessionManagementService,
    private val accountService: AccountService,
    private val authContextService: AuthContextService,
    private val authPolicy: AuthPolicy,
    private val toolRegistry: ToolHandlerRegistry,
    private val channelAccessGuard: ChannelAccessGuard,
    private val processCancellationService: ProcessCancellationService
) {

    fun initializeChannel(bindingKeyRef: String, requestedRequiredAcr: String?): ChannelResponse {
        val channelId = sessionManagementService
            .getOrCreateChannelSession(bindingKeyRef, ChannelSession.Channel.APP, CHANNEL_TTL)
            .channelSessionId!!
        requestedRequiredAcr?.let { sessionManagementService.raiseChannelRequiredAcr(channelId, it) }

        val channel = sessionManagementService.findChannelSessionById(channelId)!!
        if (sessionManagementService.findActiveProcessSession(channelId) != null) {
            return buildResponseForChannel(channel)
        }
        return if (channel.accountId == null) startRegistration(channel) else resumeOrStartLogin(channel)
    }

    fun getChannel(channelSessionId: UUID, bindingKeyRef: String): ChannelResponse =
        buildResponseForChannel(channelAccessGuard.requireChannel(channelSessionId, bindingKeyRef))

    fun raiseRequiredAcr(channelSessionId: UUID, bindingKeyRef: String, requiredAcr: String): ChannelResponse {
        val channel = channelAccessGuard.requireChannel(channelSessionId, bindingKeyRef)
        sessionManagementService.raiseChannelRequiredAcr(channelSessionId, requiredAcr)
        val refreshed = sessionManagementService.findChannelSessionById(channelSessionId)!!

        val floor = refreshed.requiredAcr ?: AcrLevels.DEFAULT_REQUIRED_ACR
        return if (authPolicy.isSatisfied(currentEvidence(refreshed), floor)) {
            buildResponseForChannel(refreshed)
        } else {
            startStepUp(refreshed, floor)
        }
    }

    /** Abandons whatever REGISTRATION/LOGIN/STEP_UP is currently active and offers a fresh start where applicable. */
    fun cancelActiveProcess(channelSessionId: UUID, bindingKeyRef: String): ChannelResponse {
        val channel = channelAccessGuard.requireChannel(channelSessionId, bindingKeyRef)
        val active = sessionManagementService.findActiveProcessSession(channelSessionId)
            ?: throw OrchestratorException.invalidState("No active process to cancel for this channel")

        processCancellationService.cancel(active, channel)

        val refreshed = sessionManagementService.findChannelSessionById(channelSessionId)!!
        return when {
            refreshed.state == ChannelState.AUTHENTICATED -> buildResponseForChannel(refreshed)
            refreshed.accountId == null -> startRegistration(refreshed)
            else -> resumeOrStartLogin(refreshed)
        }
    }

    private fun startRegistration(channel: ChannelSession): ChannelResponse {
        val channelId = channel.channelSessionId!!
        sessionManagementService.updateChannelState(channelId, ChannelState.REGISTERING)
        val processSession = sessionManagementService.createRegistrationProcessSession(channelId, PROCESS_TTL)

        // Same skip-if-single-candidate rule as ENROLL/AUTH (docs/04-orchestrierung.md #1): with
        // exactly one ident method, there is nothing to choose between.
        val identOptions = toolRegistry.descriptors().filter { it.category == ToolCategory.IDENT }.map { it.toolId }
        val offer = CandidateOffering.resolve(identOptions, "registration", "selectIdentificationMethod")
        applyNext(processSession, offer.next)
        sessionManagementService.updateProcessSession(processSession)

        return buildResponseForChannel(sessionManagementService.findChannelSessionById(channelId)!!, offer.stepData)
    }

    private fun resumeOrStartLogin(channel: ChannelSession): ChannelResponse {
        val channelId = channel.channelSessionId!!
        val evidence = currentEvidence(channel)
        val floor = channel.requiredAcr ?: AcrLevels.DEFAULT_REQUIRED_ACR

        if (authPolicy.isSatisfied(evidence, floor)) {
            sessionManagementService.updateChannelState(channelId, ChannelState.AUTHENTICATED)
            return buildResponseForChannel(sessionManagementService.findChannelSessionById(channelId)!!)
        }

        // Accurately reflect "not currently authenticated" while the login is in progress -
        // docs/02-domaenenmodell.md #3 models "start login" as leaving from ANONYMOUS. Without
        // this, a cancelled/abandoned login would leave the channel stuck reporting a stale
        // AUTHENTICATED from a previous session.
        sessionManagementService.updateChannelState(channelId, ChannelState.ANONYMOUS)
        val account = accountService.findAccount(channel.accountId!!)
            ?: throw OrchestratorException.processGone("Account not found for channel $channelId")
        val processSession = sessionManagementService.createLoginProcessSession(channelId, PROCESS_TTL)
        val offer = CandidateOffering.resolve(authPolicy.candidateTools(evidence, floor, account), "auth")
        applyNext(processSession, offer.next)
        sessionManagementService.updateProcessSession(processSession)

        return buildResponseForChannel(sessionManagementService.findChannelSessionById(channelId)!!, offer.stepData)
    }

    private fun startStepUp(channel: ChannelSession, requiredAcr: String): ChannelResponse {
        val channelId = channel.channelSessionId!!
        sessionManagementService.updateChannelState(channelId, ChannelState.STEP_UP_IN_PROGRESS)

        val account = accountService.findAccount(channel.accountId!!)
            ?: throw OrchestratorException.processGone("Account not found for channel $channelId")
        val evidence = currentEvidence(channel)
        val processSession = sessionManagementService.createStepUpProcessSession(channelId, requiredAcr, PROCESS_TTL)
        processSession.startingAcr = authPolicy.resolveAcr(evidence)
        val offer = CandidateOffering.resolve(authPolicy.candidateTools(evidence, requiredAcr, account), "auth")
        applyNext(processSession, offer.next)
        sessionManagementService.updateProcessSession(processSession)

        return buildResponseForChannel(sessionManagementService.findChannelSessionById(channelId)!!, offer.stepData)
    }

    private fun applyNext(processSession: ProcessSession, next: Next) {
        if (next.type == "tool") processSession.setNextTool(next.toolId!!, next.step) else processSession.setNextFlow(next.context!!, next.step)
    }

    private fun currentEvidence(channel: ChannelSession): AuthEvidence {
        val authContext = channel.authContextId?.let { authContextService.getAuthContext(it) }
        return AuthEvidence(authContext?.currentAmr ?: emptyList(), authContext?.currentFactorTypes ?: emptySet())
    }

    private fun buildResponseForChannel(channel: ChannelSession, stepData: Map<String, Any?>? = null): ChannelResponse {
        val authContext = channel.authContextId?.let { authContextService.getAuthContext(it) }
        val active = sessionManagementService.findActiveProcessSession(channel.channelSessionId!!)
        val next = when {
            active?.nextType == "tool" -> Next.tool(active.nextToolId!!, active.nextStep!!)
            active?.nextType == "flow" -> Next.flow(active.nextContext!!, active.nextStep!!)
            channel.state == ChannelState.AUTHENTICATED -> Next.flow("authentication", "authenticated")
            else -> null
        }
        return ChannelResponse(
            channelSessionId = channel.channelSessionId!!,
            state = channel.state?.name ?: ChannelState.ANONYMOUS.name,
            currentAcr = authContext?.currentAcr,
            currentAmr = authContext?.currentAmr,
            stepData = stepData,
            next = next
        )
    }

    companion object {
        private val CHANNEL_TTL: Duration = Duration.ofDays(30)
        private val PROCESS_TTL: Duration = Duration.ofMinutes(60)
    }
}
