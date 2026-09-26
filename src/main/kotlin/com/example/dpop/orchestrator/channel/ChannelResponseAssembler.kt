package com.example.dpop.orchestrator.channel

import com.example.dpop.orchestrator.session.SessionExpiredException
import com.example.dpop.texts.Text
import com.example.dpop.orchestrator.kernel.ChannelType
import com.example.dpop.account.AccountService
import com.example.dpop.account.AuthMethodView
import com.example.dpop.orchestrator.kernel.OrchestratorException
import com.example.dpop.orchestrator.journey.Action
import com.example.dpop.orchestrator.kernel.AuthIntent
import com.example.dpop.orchestrator.journey.JourneyService
import com.example.dpop.orchestrator.journeytrace.JourneyTraceResponse
import com.example.dpop.orchestrator.journeytrace.JourneyTraceService
import com.example.dpop.orchestrator.journey.state.ConfirmPeerLoginState
import com.example.dpop.orchestrator.journey.state.ManageAuthMethodsState
import com.example.dpop.orchestrator.policy.AuthEvidence
import com.example.dpop.orchestrator.policy.AuthPolicy
import com.example.dpop.orchestrator.kernel.AcrLevels
import com.example.dpop.orchestrator.tool.ToolHandlerRegistry
import com.example.dpop.orchestrator.kernel.AmrSource
import com.example.dpop.orchestrator.session.AuthContextService
import com.example.dpop.orchestrator.session.AuthEvidenceService
import com.example.dpop.orchestrator.session.ChannelCreationThrottleService
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.orchestrator.session.LiveChannel
import com.example.dpop.orchestrator.session.SessionManagementService
import com.example.dpop.orchestrator.session.TokenProvider
import com.example.dpop.orchestrator.session.TokenService
import com.example.dpop.orchestrator.session.toCoreEvidence
import com.example.dpop.tool_api.ActiveMethodView
import com.example.dpop.tool_api.AuthData
import com.example.dpop.tool_api.ChannelBlock
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_api.authority
import com.example.dpop.tool_api.anchorRule
import com.example.dpop.tool_api.isLocalAnchor
import com.example.dpop.tool_api.ChannelResponse
import com.example.dpop.tool_api.DemoInfo
import com.example.dpop.tool_api.DemoSession
import com.example.dpop.tool_api.Next
import com.example.dpop.tool_api.PersonDirectory
import com.example.dpop.tool_spi.AcrLevel
import java.time.Duration
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.example.dpop.orchestrator.session.forLog
import com.example.dpop.tool_spi.StepData
import org.springframework.stereotype.Component

/**
 * Builds the one response envelope (docs/05-api.md #2) from a channel's current state: the channel
 * block, the next step, the demo block and the KEYCLOAK auth data. Split off [ChannelService]
 * (review 2026-09-26, A-6), which keeps the use cases; this class decides nothing, it only reads
 * and assembles - for [ChannelService] and `ToolControllerSupport` alike.
 */
@Component
@Transactional
class ChannelResponseAssembler(
    private val accountService: AccountService,
    private val authEvidenceService: AuthEvidenceService,
    private val authPolicy: AuthPolicy,
    private val journeyService: JourneyService,
    private val personDirectory: PersonDirectory,
    private val toolRegistry: ToolHandlerRegistry,
    private val demoDisclosure: DemoDisclosure,
) {

    fun respond(channel: ChannelSession, next: Next? = null, stepData: StepData? = null): ChannelResponse {
        // A terminal channel (docs/02-domaenenmodell.md #3) never has a next - regardless of what
        // a caller passed in, so no caller (e.g. a strategy's own now-meaningless placeholder
        // after account deletion) can accidentally resurrect a dead channel with a stray next.
        val resolved = if (channel.state?.isTerminal == true) {
            null
        } else {
            next ?: journeyService.findActive(channel.channelSessionId!!)?.let { journeyService.nextOf(it, channel) }
                ?: if (channel.state == ChannelState.AUTHENTICATED) Next.AUTHENTICATED else null
        }
        return ChannelResponse(
            channel = buildChannelBlock(channel, includeAccountFields = true),
            next = resolved,
            stepData = stepData,
            demo = demoInfo(channel),
            authData = authDataFor(channel)
        )
    }

    /**
     * `KEYCLOAK`-only (docs/05-api.md Abschnitt 3) - `null` for `APP`. Used both by
     * [respond] and by `ToolControllerSupport`'s own response building, so every KEYCLOAK
     * response carries it, entry point and tool activation/PATCH alike, not just this class's own.
     */
    fun authDataFor(channel: ChannelSession): AuthData? {
        if (channel.channel != ChannelType.KEYCLOAK) return null
        val evidence = channel.authEvidenceId?.let { authEvidenceService.getAuthEvidence(it) }
        val amr = evidence?.currentAmr?.associateWith { evidence.currentAmrSource[it] ?: AmrSource.ORCHESTRATOR }
        val acr = evidence?.let {
            val account = channel.accountId?.let { id -> accountService.findAccount(id) }
            authPolicy.resolveAcr(it.toCoreEvidence(), account)
        }
        return AuthData(accountId = channel.accountId, acr = acr?.value, amr = amr)
    }

    /**
     * Same demo-only journey-chain view as ToolControllerSupport's, for channel-level responses
     * (tool_api/Envelope.kt, JourneyDebugStep). Assembled by [DemoDisclosure], never here - that
     * bean is the one place that decides whether this deployment discloses demo values at all.
     */
    private fun demoInfo(channel: ChannelSession): DemoInfo? {
        val journeys = journeyService.debugChain(channel)
        val accountId = channel.accountId
        val personId = accountId?.let { accountService.findAccount(it)?.personId }
        return demoDisclosure.assemble(accountId, personId, journeys, session = demoSession(channel))
    }

    /**
     * Who the session belongs to and what it has proven so far - for the demo column, which shows
     * it at every step. Null until something was proven: before that there is nothing to show but
     * "not signed in", which the client says on its own. Same acr resolution as [buildChannelBlock].
     */
    fun demoSession(channel: ChannelSession): DemoSession? {
        if (!channel.hasProvenFactor) return null
        val evidence = channel.authEvidenceId?.let { authEvidenceService.getAuthEvidence(it) }
        val account = channel.accountId?.let { accountService.findAccount(it) }
        return DemoSession(
            authenticated = channel.state == ChannelState.AUTHENTICATED,
            personName = account?.personId?.let { personDirectory.displayName(it) },
            acr = evidence?.let { authPolicy.resolveAcr(it.toCoreEvidence(), account) }?.value,
            amr = evidence?.currentAmr ?: emptyList()
        )
    }

    /**
     * The channel-level block shared by every response, channel- and tool-level alike
     * (docs/05-api.md #2) - public so the tool controllers can attach it without a separate
     * `GET /channels` round-trip.
     *
     * [includeAccountFields] gates `currentAcr`/`currentAmr`/`activeMethods`, default `false`:
     * tool controllers are the common caller and never need them - the security-summary screen
     * that reads these is fetched on demand, so it never belongs in the core flow contract. Even
     * when `true`, they only actually appear once [ChannelSession.hasProvenFactor] - a
     * recognized-but-unproven device (`accountId` set via `DeviceAccountLink`, no evidence yet)
     * must not leak the account's active methods before anything was proven on THIS channel.
     */
    fun buildChannelBlock(channel: ChannelSession, includeAccountFields: Boolean = false): ChannelBlock {
        val channelType = checkNotNull(channel.channel) { "Channel without a channel type" }.name
        if (!includeAccountFields || !channel.hasProvenFactor) {
            return ChannelBlock(
                channelSessionId = channel.channelSessionId!!,
                channelType = channelType,
                state = channel.state?.name ?: ChannelState.ANONYMOUS.name,
                hasProvenFactor = channel.hasProvenFactor
            )
        }
        val evidence = channel.authEvidenceId?.let { authEvidenceService.getAuthEvidence(it) }
        val account = channel.accountId?.let { accountService.findAccount(it) }
        val currentAcr = evidence?.let { authPolicy.resolveAcr(it.toCoreEvidence(), account) }
        return ChannelBlock(
            channelSessionId = channel.channelSessionId!!,
            channelType = channelType,
            state = channel.state?.name ?: ChannelState.ANONYMOUS.name,
            hasProvenFactor = channel.hasProvenFactor,
            currentAcr = currentAcr?.value,
            currentAmr = evidence?.currentAmr,
            activeMethods = toActiveMethodViews(account?.activeAuthenticationMethods)
        )
    }

    /**
     * `maxAcr`/`factorTypes` come from the tool catalog (a method's own, account-independent
     * ceiling); `enrolledUnderAcr`/`effectiveAcr` from the account's own enrollment record (the
     * ADR-5 cap, [DefaultAuthPolicy.canAccountReach]'s same `AcrLevel.min` calculation) - surfaced
     * here so the UI can show WHY a method might not reach as far as its own catalog entry
     * promises, instead of that only being discoverable later as a confusing rejection.
     */
    fun toActiveMethodViews(methods: List<AuthMethodView>?): List<ActiveMethodView> =
        methods.orEmpty().map { m ->
            val descriptor = toolRegistry.descriptors().firstOrNull { it.method == m.method }
            ActiveMethodView(
                id = checkNotNull(m.id) { "Active method without an id" },
                method = m.method,
                label = m.label,
                factorTypes = descriptor?.factorTypes?.toList(),
                        maxAcr = descriptor?.maxAcr?.value,
                enrolledUnderAcr = m.enrolledUnderAcr,
                effectiveAcr = descriptor?.let { AcrLevel.min(AcrLevel.of(m.enrolledUnderAcr), it.maxAcr) }?.value
            )
        }
}
