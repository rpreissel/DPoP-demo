package com.example.dpop.orchestrator.journey

import com.example.dpop.account.AccountService
import com.example.dpop.orchestrator.policy.AuthEvidence
import com.example.dpop.orchestrator.policy.AuthPolicy
import com.example.dpop.orchestrator.session.AcrLevels
import com.example.dpop.orchestrator.session.AuthEvidenceService
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.session.SessionManagementService
import com.example.dpop.orchestrator.session.toCoreEvidence
import com.example.dpop.orchestrator.tool.ToolHandlerRegistry
import com.example.dpop.tool_spi.AcrLevel
import org.springframework.stereotype.Component

/**
 * The reading phase of a transition: everything a strategy may look at, gathered from the durable
 * truth (account, evidence, device link, flags) into the read-only [JourneyContext].
 *
 * Its own class because [JourneyService] re-derives this after EVERY action
 * ([Transition.Perform]'s recursive step) - a strategy must never see a stale, pre-action
 * picture. Having exactly one place that assembles the context is what makes that guarantee
 * mechanical instead of a rule each call site has to remember.
 */
@Component
class JourneyContextFactory(
    private val accountService: AccountService,
    private val authEvidenceService: AuthEvidenceService,
    private val sessionManagementService: SessionManagementService,
    private val authPolicy: AuthPolicy,
    private val toolRegistry: ToolHandlerRegistry,
    private val routing: JourneyRouting,
    private val featureFlagProviders: List<FeatureFlagProvider>
) {
    fun contextFor(journey: AuthJourney, channel: ChannelSession): JourneyContext {
        val accountId = journey.accountId ?: channel.accountId
        val evidence = channel.authEvidenceId?.let { authEvidenceService.getAuthEvidence(it) }
        return JourneyContext(
            channel = checkNotNull(channel.channel) { "ChannelSession without a channel type" },
            account = accountId?.let { accountService.findAccount(it) },
            evidence = evidence?.toCoreEvidence() ?: AuthEvidence(emptyList()),
            acrFloor = acrFloorOf(channel),
            bindingKeyRef = channel.bindingKeyRef,
            linkedAccountId = channel.bindingKeyRef?.let { sessionManagementService.findLinkedAccountId(it) },
            isSubJourney = journey.parentJourneyId != null,
            policy = authPolicy,
            catalog = toolRegistry,
            availableTools = routing.availableToolsOf(channel),
            featureFlags = featureFlagProviders.flatMapTo(mutableSetOf()) { it.activeFlags() }
        )
    }

    fun acrFloorOf(channel: ChannelSession): AcrLevel =
        channel.acrFloor?.let(AcrLevel::of) ?: AcrLevels.DEFAULT_REQUIRED_ACR

    /** Live, not cached (docs/orchestrator/policy/AuthEvidence.kt): `currentAcr` is never stored, only ever recomputed from the evidence that's actually there. */
    fun currentAcrOf(channel: ChannelSession): AcrLevel {
        val evidence = channel.authEvidenceId?.let { authEvidenceService.getAuthEvidence(it) } ?: return AcrLevel.NONE
        val account = channel.accountId?.let { accountService.findAccount(it) }
        return authPolicy.resolveAcr(evidence.toCoreEvidence(), account)
    }
}
