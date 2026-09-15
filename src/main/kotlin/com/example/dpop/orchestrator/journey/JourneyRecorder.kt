package com.example.dpop.orchestrator.journey

import com.example.dpop.account.AccountService
import com.example.dpop.orchestrator.journeylog.JourneyLogService
import com.example.dpop.orchestrator.policy.MethodEvidence
import com.example.dpop.orchestrator.policy.MethodName
import com.example.dpop.orchestrator.policy.evidenceAxis
import com.example.dpop.orchestrator.session.AmrSource
import com.example.dpop.orchestrator.session.AuthEvidenceService
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.session.SessionManagementService
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.stereotype.Component

/**
 * Everything a completed step writes OUTSIDE the state machine's own tables: native evidence
 * syncs, per-tool evidence updates, session events and the account's identification log.
 * Deliberately split off [JourneyService] so the service itself stays the state machine and
 * nothing else; this class records what already happened, it never decides anything.
 */
@Component
class JourneyRecorder(
    private val authEvidenceService: AuthEvidenceService,
    private val sessionManagementService: SessionManagementService,
    private val accountService: AccountService,
    private val journeyLogService: JourneyLogService,
    private val journeyLogDetails: JourneyLogDetails,
    private val codec: JourneyStateCodec
) {

    /**
     * Takes the REAL `MethodEvidence` list, not several per-field maps keyed by method - the
     * caller already has a complete record per method by the time it calls this.
     */
    fun mergeEvidence(journey: AuthJourney, channel: ChannelSession, source: String, updates: List<MethodEvidence>) {
        // [source]'s set BEFORE the update - compared against [updates] below to decide whether
        // this call actually changed anything worth logging. Needed because every caller resends
        // its COMPLETE current set on every call, no delta (docs/05-api.md Abschnitt 3,
        // "kein Delta") - e.g. every LoA-2 selectMethod poll re-reports the very same native
        // password proof, and logging that identically on each poll would drown the one real entry
        // (the first time it was proven) in noise.
        val before = channel.authEvidenceId
            ?.let { authEvidenceService.getAuthEvidence(it) }?.amrEvidence.orEmpty()
            .filter { it.source == source }
            .map { Triple(it.method, it.loa, it.amrSourceId) }
            .toSet()
        val after = updates.map { Triple(it.method.value, it.loa.value, it.amrSourceId) }.toSet()

        authEvidenceService.attachToChannel(channel, source, updates)
        sessionManagementService.recordEvent(channel.channelSessionId, journey.journeyId, "EVIDENCE_UPDATE_APPLIED", source)
        if (before != after) {
            // The generic advance() call right after this logs the EvidenceReported transition
            // itself (with acrFloor/resolvedAcr), but not WHAT changed - this records that
            // (docs/05-api.md Abschnitt 3: native/external evidence, source=kc) - without
            // it, the journey log would show every tool outcome in full but go silent on every
            // Keycloak-native factor, even though it's just as real a step in the journey's path.
            // snake_case, not PascalCase: JourneyService buckets this at the machine's discretion,
            // not as a real `event::class.simpleName` transition (naming convention, docs/ideen/
            // journey-strategie-vereinheitlichung.md #4) - and deliberately not named similarly to
            // "EvidenceReported" (the real transition's own log entry), which used to invite
            // confusing the two.
            journeyLogService.record(
                channel, journey, "native_evidence_synced",
                journeyState = codec.read(journey)::class.simpleName,
                detail = mapOf("source" to source, "methods" to journeyLogDetails.methodEvidenceDetail(updates))
            )
        }
    }

    /**
     * The MethodEvidence bookkeeping every tool-outcome [Action] needs alike, once: the cap
     * `min(achievedAcr, enrolledUnderAcr)` a caller already folded into [effectiveAcr] where it
     * applies ([Action.AcceptProof]) lives ONLY there - here just records what the outcome proved,
     * at whatever level the caller decided actually counts.
     */
    fun recordToolCompletion(
        journey: AuthJourney,
        channel: ChannelSession,
        tool: ToolDescriptor,
        outcome: ToolOutcome.Completed,
        effectiveAcr: AcrLevel?
    ) {
        val authEvidenceId = checkNotNull(channel.authEvidenceId) { "AuthEvidence missing after ${tool.toolId}" }
        val accountId = channel.accountId
        val updates = outcome.amr.map { method ->
            MethodEvidence(
                method = MethodName(method),
                // This run's own achieved/capped level if it has one, else the tool's own declared ceiling.
                loa = effectiveAcr ?: tool.maxAcr,
                // The account's own enrollment record for this method (docs/06-ablaeufe.md #1)
                // - the same idiom Action.AcceptProof already reads.
                enrolledUnderAcr = accountId?.let { accountService.findActiveMethod(it, method)?.enrolledUnderAcr }?.let(AcrLevel::of),
                factorTypes = outcome.factorTypes,
                source = AmrSource.ORCHESTRATOR,
                amrSourceId = tool.toolId.value,
                axis = tool.evidenceAxis(),
            )
        }
        authEvidenceService.applyEvidence(authEvidenceId, updates)
        sessionManagementService.recordEvent(
            channel.channelSessionId, journey.journeyId, "TOOL_COMPLETED:${tool.toolId}", "orchestrator"
        )
    }

    fun recordIdentification(
        journey: AuthJourney,
        channel: ChannelSession,
        tool: ToolDescriptor,
        outcome: ToolOutcome.Completed.Identified
    ) {
        accountService.addIdentification(
            checkNotNull(journey.accountId),
            tool.method,
            outcome.achievedAcr?.value,
            outcome.auditDetails.orEmpty() + mapOf(
                "channel" to channel.channel?.name,
                "journeyId" to journey.journeyId.toString()
            )
        )
    }
}
