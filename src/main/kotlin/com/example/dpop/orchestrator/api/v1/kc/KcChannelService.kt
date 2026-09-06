package com.example.dpop.orchestrator.api.v1.kc

import com.example.dpop.account.AccountService
import com.example.dpop.orchestrator.api.v1.KcChannelAccessGuard
import com.example.dpop.orchestrator.api.v1.OrchestratorException
import com.example.dpop.orchestrator.api.v1.channel.ChannelService
import com.example.dpop.orchestrator.journey.Action
import com.example.dpop.orchestrator.journey.JourneyService
import com.example.dpop.orchestrator.kc.PeerAuthAssertion
import com.example.dpop.orchestrator.policy.AuthEvidence
import com.example.dpop.orchestrator.policy.MethodEvidence
import com.example.dpop.orchestrator.policy.MethodName
import com.example.dpop.orchestrator.session.AcrLevel
import com.example.dpop.orchestrator.session.AcrLevels
import com.example.dpop.orchestrator.session.AmrSource
import com.example.dpop.orchestrator.session.AuthEvidenceService
import com.example.dpop.orchestrator.session.SessionManagementService
import com.example.dpop.orchestrator.session.toMethodEvidence
import com.example.dpop.tool_api.ChannelResponse
import java.time.Duration
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The kc-facade's one facade-specific endpoint's service (docs/ideen/web-keycloak-kanal.md #6):
 * upsert semantics on a client(=Keycloak)-chosen [UUID] - resumes an existing channel or creates
 * it, then delegates to [ChannelService.resumeChannel] exactly like the App channel does, so
 * "which tool comes next" stays decided in exactly one place.
 */
@Service
@Transactional
class KcChannelService(
    private val sessionManagementService: SessionManagementService,
    private val kcChannelAccessGuard: KcChannelAccessGuard,
    private val channelService: ChannelService,
    private val journeyService: JourneyService,
    private val accountService: AccountService,
    private val authEvidenceService: AuthEvidenceService,
    private val restoreDataCodec: RestoreDataCodec,
    private val nativeAuthenticatorRegistry: NativeAuthenticatorRegistry
) {

    fun upsertChannel(
        channelSessionId: UUID,
        assertion: PeerAuthAssertion,
        accountId: Long?,
        targetAcr: String?,
        amr: List<AmrEntry>? = null,
        restoreDataToken: String? = null,
        restoreDataKcSessionId: String? = null,
        availableTools: List<String>? = null
    ): ChannelResponse {
        // restoreDataToken is the bulk, one-shot counterpart of accountId/amr above (docs/ideen/
        // web-keycloak-kanal.md #6) - a prior, unrelated flow run's own state, resubmitted
        // verbatim. decode() checks the token is bound to [restoreDataKcSessionId] - Keycloak's
        // actual, durable UserSessionModel id, sent explicitly alongside the token because it is
        // NOT the same value as assertion.channelAnchor (which is only ever this flow run's own
        // channelSessionId, see PeerAuthAssertion's own doc) - anything else (wrong session,
        // tampered, expired) comes back null, same as "nothing to restore". A restored method
        // keeps ITS OWN original `source` (e.g. still `orchestrator` if that's what it was before
        // the restore) - MethodEvidence.source travels with each entry, never assumed `kc` just
        // because it arrived via this channel. Never coincides with a non-empty [amr] in practice:
        // the only caller that ever sends a restoreDataToken (OrchestratorResumeAuthenticator)
        // always sends an empty amr alongside it, and always mints a brand-new channelSessionId -
        // see [restoredFactors]/[liveFactors] below, applied separately for exactly that reason.
        val restoreData = restoreDataToken?.let { restoreDataCodec.decode(it, restoreDataKcSessionId) }
        val effectiveAccountId = accountId ?: restoreData?.accountId
        val restoredFactors = restoreData?.evidence?.factors.orEmpty()
        // method/maxAcr/factorTypes are fixed per authenticator TYPE, not resent per proof - see
        // NativeAuthenticatorDescriptor's own doc. Fails fast on an unknown nativeToolId for the
        // same reason the accountId check below does: a silently-empty descriptor would price the
        // proof as if it contributed nothing, an unrelated-looking bug three steps downstream.
        val liveFactors = amr.orEmpty().map { entry ->
            val descriptor = nativeAuthenticatorRegistry.descriptorFor(entry.nativeToolId)
                ?: throw OrchestratorException.notFound("Unknown nativeToolId: ${entry.nativeToolId}")
            MethodEvidence(
                MethodName(descriptor.method),
                AcrLevel(descriptor.maxAcr),
                // Deliberately UNCAPPED (docs/ideen/web-keycloak-kanal.md #9), not the method's
                // own loa: the enrolledUnderAcr cap exists to stop an orchestrator combination
                // from self-escalating past what an account's real enrollment history actually
                // supports - a defense against a claim the orchestrator itself never verified.
                // Keycloak's native report is already a full "Selbstauskunft" the orchestrator
                // trusts wholesale (docs #3); capping the COMBINATION on top of that defends
                // against nothing extra, it only breaks the documented "two distinct factor
                // types earn one tier above either alone" rule for the exact case it exists for.
                AcrLevel(AcrLevels.HIGHEST),
                descriptor.factorTypes,
                source = AmrSource.KEYCLOAK,
                // nativeToolId prefixed on, colon-separated: amrSourceId alone (Keycloak's execution
                // id) is opaque to a human reading the journey log - this way the log itself names
                // WHICH native authenticator proved it, without a separate field every consumer of
                // MethodEvidence would need to carry along. Stable across re-reports of the same
                // proof (entry.nativeToolId never changes for a given execution), so the "refresh,
                // not a new proof" comparisons elsewhere (amrSourceId equality) keep working.
                amrSourceId = "${entry.nativeToolId}:${entry.amrSourceId}",
            )
        }
        // Fails fast and clearly - without this, an unknown accountId still gets bound to the
        // channel (accountId alone is just a Long) and only surfaces once some later step tries
        // to actually resolve the account, as an unrelated-looking internal error.
        if (effectiveAccountId != null && accountService.findAccount(effectiveAccountId) == null) {
            throw OrchestratorException.notFound("Account not found: $effectiveAccountId")
        }

        val isFreshChannel = sessionManagementService.findChannelSessionById(channelSessionId) == null
        if (isFreshChannel) {
            sessionManagementService.createKcChannelSession(
                channelSessionId,
                assertion.channelAnchor,
                effectiveAccountId,
                CHANNEL_TTL,
                // The Web channel's own declaration of what it can render (WebToolAvailability on the
                // extension side, one toolId per registered WebToolRenderer factory) - taken verbatim,
                // exactly like the App channel's own client-declared availableTools
                // (ChannelService.initializeChannel), never widened back to the orchestrator's whole
                // catalog: an unfiltered "everything" default is exactly the App-Kanal-parity gap
                // DPoP-demo-3yd.6 closes.
                availableTools.orEmpty().toSet()
            )
        } else {
            // Even a guessed channelSessionId is never enough on its own - the assertion must
            // independently claim the same kc-anchor this channel was opened with (docs/ideen/
            // web-keycloak-kanal.md #4).
            val channel = kcChannelAccessGuard.requireChannel(channelSessionId, assertion)
            // Step-up (docs/ideen/web-keycloak-kanal.md #6): binds the channel to the account
            // Keycloak already knows, as soon as it first appears - never overwritten once set,
            // a later request naming a different account would be a mismatch, not a rebind.
            if (effectiveAccountId != null && channel.accountId == null) {
                channel.accountId = effectiveAccountId
                sessionManagementService.updateChannelSession(channel)
            }
        }

        targetAcr?.let { sessionManagementService.raiseChannelAcrFloor(channelSessionId, it) }

        // restoredFactors only ever arrives paired with a channel THIS call just created (see
        // restoreDataToken's own doc) - applied as the entry journey's own Anfangs-Übergang
        // (docs/ideen/journey-strategie-vereinheitlichung.md #3, JourneyService.start's
        // `seedAction`), so the journey's own first decision already sees the real picture, not a
        // stale, evidence-blind snapshot immediately superseded a moment later.
        var response = if (isFreshChannel && restoredFactors.isNotEmpty()) {
            channelService.resumeChannel(
                sessionManagementService.findChannelSessionById(channelSessionId)!!,
                Action.ApplyRestoredEvidence(AmrSource.KEYCLOAK, restoredFactors)
            )
        } else {
            channelService.resumeChannel(sessionManagementService.findChannelSessionById(channelSessionId)!!)
        }

        // What a native Keycloak authenticator already established THIS run (docs/ideen/web-
        // keycloak-kanal.md #8/#9) - combined into the SAME evidence an orchestrator tool proof
        // would produce, via JourneyService.applyEvidenceUpdate, then re-derived into the response
        // actually returned. Always applied to an already-existing journey (either the one just
        // started above, or one from an earlier call on this same channel) - never restoredFactors
        // too, which this call's earlier branch already applied before any journey existed.
        if (liveFactors.isNotEmpty()) {
            val journey = journeyService.findActive(channelSessionId)
            if (journey != null) {
                val channel = sessionManagementService.findChannelSessionById(channelSessionId)!!
                journeyService.applyEvidenceUpdate(journey, channel, AmrSource.KEYCLOAK, liveFactors)
                response = channelService.resumeChannel(sessionManagementService.findChannelSessionById(channelSessionId)!!)
            }
        }

        return response
    }

    /**
     * Fetched once by the Authenticator's end-of-flow lifecycle hook (docs/ideen/web-keycloak-
     * kanal.md #6), never bundled into [upsertChannel]'s own response - see [RestoreData]'s own
     * doc for why. [kcSessionId] is what the returned token gets bound to (see
     * [RestoreDataCodec]) - the fresh UserSessionModel id the caller just learned from Keycloak's
     * own runtime, which this backend has no independent way to know yet at this point. `null` for
     * a channel with no evidence at all (nothing worth restoring).
     */
    fun restoreData(channelSessionId: UUID, assertion: PeerAuthAssertion, kcSessionId: String): String? {
        val channel = kcChannelAccessGuard.requireChannel(channelSessionId, assertion)
        // This is the one call every completed kc flow run makes unconditionally
        // (OrchestratorResumeAuthenticator.onTopFlowSuccess, docs/ideen/web-keycloak-kanal.md #6) -
        // piggybacking the durable session id's first-ever appearance onto it means RetentionJob
        // (DPoP-demo-f9o.12) gets it for free, with no separate write path or Keycloak-extension
        // change needed.
        if (channel.durableKcSessionId != kcSessionId) {
            channel.durableKcSessionId = kcSessionId
            sessionManagementService.updateChannelSession(channel)
        }
        val storedEvidence = channel.authEvidenceId?.let { authEvidenceService.getAuthEvidence(it) }
        val factors = storedEvidence?.amrEvidence?.map { it.toMethodEvidence() }
        if (channel.accountId == null && factors.isNullOrEmpty()) return null
        val coreEvidence = factors?.takeIf { it.isNotEmpty() }?.let { AuthEvidence(it) }
        return restoreDataCodec.encode(RestoreData(accountId = channel.accountId, evidence = coreEvidence), kcSessionId)
    }

    companion object {
        // One Keycloak flow run's worth - shorter than the App channel's, which must survive a
        // whole device session.
        private val CHANNEL_TTL: Duration = Duration.ofMinutes(30)
    }
}
