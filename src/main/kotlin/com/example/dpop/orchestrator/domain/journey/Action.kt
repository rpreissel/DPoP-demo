package com.example.dpop.orchestrator.domain.journey

import com.example.dpop.orchestrator.journey.JourneyActionExecutor
import com.example.dpop.orchestrator.journey.JourneyService
import com.example.dpop.account.AccountProfile
import com.example.dpop.orchestrator.domain.policy.MethodEvidence
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ToolOutcome
import com.example.dpop.orchestrator.domain.AuthIntent

/**
 * A named side effect a strategy decided, for [JourneyService] to actually execute - the strategy
 * never acts itself (see [IntentStrategy]'s own class doc). Two origins share this one
 * vocabulary: the first three variants answer "what did a just-completed tool establish" (carried
 * by a [Transition.Perform] returned in reaction to [JourneyEvent.Completed], with the very
 * [ToolDescriptor]/[ToolOutcome] that arrived with it); the rest are a strategy's own actions,
 * likewise wrapped in [Transition.Perform].
 *
 * **Trust levels, made explicit on purpose:** [RecordIdentification] and [AdoptAttestation] are
 * the only two variants whose account resolution can ever land on an account OTHER than the one
 * already bound to this journey/channel - every strategy that constructs either one funnels
 * through the exact same gate in `JourneyActionExecutor` (`accountOf`, called from
 * `performRecordIdentification` and `performAdoptAttestation` alike), never a per-caller
 * reimplementation. There is deliberately no pair of variants a strategy could pick between to
 * pick which safety rule applies: which account a resolution may land on is a property of the
 * EVIDENCE (tool category, `EvidenceAxis`), never of which code path happened to call it. Every
 * other variant below only ever acts on the account already known from context - structurally
 * incapable of crossing to a different one, not merely by convention.
 */
sealed interface Action {
    /**
     * A completed IDENTIFICATION ([MethodRole.category] `IDENT`, e.g. `ident-fsc`/`ident-eid`) or
     * CORRELATION (`ident-kvnr`) tool resolved (or extended) an identity. One handler
     * (`performRecordIdentification`) for both origins: whether an account is already known from
     * context is read from the journey/channel at execution time, never pre-decided by the caller
     * - so no strategy can construct "the version that skips the merge-safety check".
     */
    data class RecordIdentification(val tool: ToolDescriptor, val outcome: ToolOutcome.Completed.Identified) : Action

    /**
     * An attribute the ACCOUNT owns was attested (e.g. a confirmed email address): record the
     * claims, materialize the anchor - but create no method instance and bind no device. The
     * counterpart to [AdoptCredential] for a value that is account infrastructure rather than a
     * credential (docs/12-entscheidungen.md, `AttributeType.authority`).
     *
     * Deliberately weaker than [RecordIdentification]: an attestation alone can extend the account already
     * in hand, but may only land on a DIFFERENT existing account when this session has already
     * proven [RecordIdentification] evidence this same journey (`EvidenceAxis.IDENTITY` - checked
     * inside `performAdoptAttestation`, not left to the caller). Possession of a mailbox is never,
     * by itself, proof of who owns the account that mailbox is already confirmed on.
     */
    data class AdoptAttestation(val tool: ToolDescriptor, val outcome: ToolOutcome.Completed.Attested) : Action

    /**
     * A new credential was enrolled. Whether this also links the device is NOT a field here:
     * it follows from the journey's own [AuthIntent.bindsDeviceImplicitly] (and, for a KEYCLOAK
     * channel, from there being no device at all).
     */
    data class AdoptCredential(
        val tool: ToolDescriptor,
        val outcome: ToolOutcome.Completed.Enrolled
    ) : Action

    /**
     * A credential was proven. Whether the tool is allowed to NAME the account it proved (a
     * lookup login, where nothing else could know it yet) is deliberately NOT a field here: it is
     * derived in `performAcceptProof` from [MethodRole.LOOKUP_AUTH] - the one role whose whole
     * contract is "resolves the account itself from a submitted identifier" - plus the live
     * journey/channel binding, and a named account that disagrees with one already bound is a
     * `409`, never a silent switch. Not a flag a strategy passes: the safety of "a tool may not
     * name any account it likes" must not rest on every caller getting it right, nor on a snapshot
     * computed when the state was built rather than when the action runs.
     */
    data class AcceptProof(
        val tool: ToolDescriptor,
        val outcome: ToolOutcome.Completed.Authenticated
    ) : Action

    /**
     * Prime a fresh channel's evidence from [methods] before this journey's own first decision -
     * the Anfangs-Übergang ([JourneyService.start]'s `seedAction`, docs/04-orchestrierung.md,
     * "RestoreData als erster Übergang"), never a strategy's own decision: no strategy ever sees this
     * action, it is applied mechanically before `initialState()` even runs.
     */
    data class ApplyRestoredEvidence(
        /**
         * Which foreign system vouches for [methods] (e.g. `AmrSource.KEYCLOAK`) - evidence is
         * merged per source, so a later report from the same one replaces its own set rather than
         * accumulating alongside it.
         */
        val source: String,
        /**
         * The COMPLETE current set from [source], never a delta: every caller re-reports
         * everything it knows on every call (docs/05-api.md Abschnitt 3).
         */
        val methods: List<MethodEvidence>
    ) : Action

    /**
     * A [ToolOutcome.Completed.Approved] tool approved a peer channel's pending request. The
     * approval's own domain effect (e.g. writing `QrLoginRequest`) already happened inside the
     * tool itself before it reported [ToolOutcome.Completed.Approved] - this action only carries
     * the outcome through the same MethodEvidence bookkeeping every other tool-outcome action gets
     * ([JourneyService.recordToolCompletion]), for consistency and auditing, not because anything
     * about THIS channel's own evidence actually changed.
     */
    data class RecordApproval(val tool: ToolDescriptor, val outcome: ToolOutcome.Completed.Approved) : Action

    /**
     * Revoke ONE authentication method of the account this session holds - the credential itself
     * goes, not just an active flag (`AccountDeletionService.revokeMethod`). Not a tool run; the
     * strategy decides it, the machine executes it (rejecting self-lockout).
     *
     * Named for what it destroys, next to [DeleteAccount] which destroys the whole account: the
     * two are deliberately not near-synonyms ("Remove" said neither what was removed nor how it
     * differed from deleting everything). Names the method, never the account: which account that
     * instance has to belong to is read from the live session, so a stale or wrong id cannot
     * reach into another account's methods.
     */
    data class RevokeAuthMethod(val methodInstanceId: String) : Action

    /**
     * Withdraw one account attribute - today only a confirmed address. Its own action rather
     * than a variant of [RevokeAuthMethod] because it destroys something else entirely: an
     * account-owned fact, not a credential. What DID depend on it falls as a consequence, worked
     * out at execution time from the catalog's own `requires` declarations, never listed here
     * (`JourneyActionExecutor.performRetractAttribute`).
     */
    data class RetractAttribute(val attributeType: AttributeType) : Action

    /**
     * Link the current device to the account this session holds - the action an accepted
     * device-binding offer asks for (see [JourneyEvent.Answered]).
     *
     * Deliberately carries NO accountId - an id a strategy stored in its own state would be
     * persisted in the journey's JSON when that state was built and read back only when the user
     * finally answers. Binding a physical device is the single most durable thing this machine
     * does (`DeviceAccountLink` outlives every journey and sends the next `FAST_ACCESS` straight
     * into that account), so it must follow the session's own current binding.
     */
    data object LinkDevice : Action

    /**
     * Delete the account this session holds, and everything it owns. An irreversible action, so
     * `JourneyActionExecutor` independently re-checks [requiredAcr] against the CURRENT evidence
     * right before executing it, exactly like it independently re-checks self-lockout before
     * [RevokeAuthMethod].
     *
     * Carries no accountId for the same reason [LinkDevice] does not - and here it was outright
     * inconsistent: the permission check ran against the session's account while the deletion
     * targeted the action's own field, so the two could name different accounts.
     */
    data object DeleteAccount : Action {
        /**
         * The ACR JourneyService independently re-checks right before executing this action.
         * Lives here rather than in `DeleteAccountStrategy` so the generic machine can
         * reference it without importing a concrete [IntentStrategy] implementation. Deleting
         * an account is no more sensitive than [selfServiceAcrFloor]'s own reasoning already
         * covers, so this is exactly that floor, not a second definition of it.
         */
        fun requiredAcr(account: AccountProfile?): AcrLevel = selfServiceAcrFloor(account)
    }
}
