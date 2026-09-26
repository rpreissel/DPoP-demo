package com.example.dpop.orchestrator.journey

import com.example.dpop.orchestrator.domain.journey.Action
import com.example.dpop.account.AccountService
import com.example.dpop.account.SignInLog
import com.example.dpop.orchestrator.journeytrace.JourneyTraceService
import com.example.dpop.orchestrator.domain.policy.MethodEvidence
import com.example.dpop.orchestrator.domain.policy.MethodName
import com.example.dpop.orchestrator.domain.policy.evidenceAxis
import com.example.dpop.orchestrator.domain.AmrSource
import com.example.dpop.orchestrator.domain.AuthIntent
import com.example.dpop.orchestrator.session.AuthEvidenceService
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.stereotype.Component
import com.example.dpop.orchestrator.session.forLog

/**
 * Everything a completed step writes OUTSIDE the state machine's own tables: native evidence
 * syncs, per-tool evidence updates, session events and the account's change log (IDENTIFIED).
 * Deliberately split off [JourneyService] so the service itself stays the state machine and
 * nothing else; this class records what already happened, it never decides anything.
 */
@Component
class JourneyRecorder(
    private val authEvidenceService: AuthEvidenceService,
    private val accountService: AccountService,
    private val signInLog: SignInLog,
    private val journeyTraceService: JourneyTraceService,
    private val journeyTraceDetails: JourneyTraceDetails,
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
        if (before != after) {
            // The generic advance() call right after this logs the EvidenceReported transition
            // itself (with acrFloor/resolvedAcr), but not WHAT changed - this records that
            // (docs/05-api.md Abschnitt 3: native/external evidence, source=kc) - without
            // it, the journey trace would show every tool outcome in full but go silent on every
            // Keycloak-native factor, even though it's just as real a step in the journey's path.
            // snake_case, not PascalCase: JourneyService buckets this at the machine's discretion,
            // not as a real `event::class.simpleName` transition (naming convention: snake_case for
            // entries that are no transition) - and deliberately not named similarly to
            // "EvidenceReported" (the real transition's own log entry), so the two cannot be
            // confused for one another.
            journeyTraceService.record(channel.forLog(), journey.forLog(), "native_evidence_synced",
                journeyState = codec.read(journey)::class.simpleName,
                detail = mapOf("source" to source, "methods" to journeyTraceDetails.methodEvidenceDetail(updates))
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
        // A role that contributes to neither axis proves nothing about THIS session, so it leaves
        // no evidence behind - whatever it reported as amr/achievedAcr. Decided here, centrally,
        // rather than by each such tool remembering to report an empty amr: a tool that forgets
        // would otherwise mint assurance its role explicitly denies (see ToolDescriptor.evidenceAxis).
        // The completion is still audited below either way - it happened, it just proved nothing.
        val axis = tool.evidenceAxis()
        val updates = if (axis == null) emptyList() else outcome.amr.map { method ->
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
                axis = axis,
            )
        }
        if (updates.isNotEmpty()) authEvidenceService.applyEvidence(authEvidenceId, updates)
    }

    /**
     * The audit row for one act of establishing identity - BOTH acts ADR-18 splits an
     * identification into, not just the attesting one: `ident-eid` proving who somebody is, and
     * `ident-kvnr` binding that person to the register. The correlation act is the one an audit
     * needs most - it is the moment the PERSON_ID anchor came to exist - so leaving it out would
     * record the proof and hide the binding.
     *
     * Which act a row was is therefore written down rather than left to be inferred from the
     * method name ([MethodRole], in `details`): a correlation step carries the achieved level of
     * the identification it rests on (`IdentKvnrDescriptor.maxAcr` - typing a number proves
     * nothing by itself, the assurance comes from `requires` plus the master-data match), and a
     * row saying `kvnr / loa2` with nothing else would read like a procedure that reached loa2 on
     * its own. The two rows of one run are tied together by their shared `journeyId`.
     */
    fun recordIdentification(
        journey: AuthJourney,
        channel: ChannelSession,
        tool: ToolDescriptor,
        outcome: ToolOutcome.Completed.Identified
    ) {
        // Passed on whole: which of it may be kept is the change log's own rule (ChangeLog.identified).
        accountService.addIdentification(
            checkNotNull(channel.accountId),
            tool.method,
            outcome.achievedAcr?.value,
            role = tool.role.name,
            report = outcome.auditDetails.orEmpty(),
        )
    }

    /**
     * The sign-in log's view of a journey that just finished (ADR-39, addendum): an entry journey
     * that authenticated the channel is a sign-in, a step-up a step-up; everything else (managing
     * methods, deleting, logging out) is no sign-in and records nothing here. [acr] is what the
     * session holds now - the level the sign-in actually reached.
     */
    fun recordSignIn(journey: AuthJourney, channel: ChannelSession, acr: AcrLevel) {
        val accountId = channel.accountId ?: return
        val intent = journey.intent ?: return
        val amr = channel.authEvidenceId?.let { authEvidenceService.getAuthEvidence(it) }?.currentAmr.orEmpty()
        when {
            intent == AuthIntent.STEP_UP -> signInLog.steppedUp(accountId, channel.channel?.name, acr.value, amr)
            intent.isEntryIntent -> signInLog.signedIn(accountId, channel.channel?.name, acr.value, amr, intent.name)
        }
    }

    /** A session the holder ended on purpose - never an expiry, which nobody asked for. */
    fun recordSignOut(channel: ChannelSession, endedBy: String) {
        val accountId = channel.accountId ?: return
        signInLog.signedOut(accountId, channel.channel?.name, endedBy)
    }
}
