package com.example.dpop.orchestrator.session

import com.example.dpop.orchestrator.policy.MethodEvidence
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class AuthEvidenceService(
    private val authEvidenceRepository: AuthEvidenceRepository,
    private val sessionManagementService: SessionManagementService
) {

    fun createForAccount(accountId: Long): AuthEvidence =
        authEvidenceRepository.save(AuthEvidence(accountId = accountId))

    fun getAuthEvidence(authEvidenceId: UUID): AuthEvidence? =
        authEvidenceRepository.findByIdOrNull(authEvidenceId)

    /** A single completed orchestrator tool's own proof (docs/04-orchestrierung.md #1) - a one-off event, merged via [AuthEvidence.addAmr]. Each [updates] entry carries its own `MethodEvidence.source`. */
    fun applyEvidence(authEvidenceId: UUID, updates: List<MethodEvidence>) {
        val evidence = authEvidenceRepository.findByIdOrNull(authEvidenceId)
            ?: throw IllegalArgumentException("AuthEvidence not found: $authEvidenceId")
        evidence.addAmr(updates)
        authEvidenceRepository.save(evidence)
    }

    /**
     * A sync of [source]'s complete, currently-valid set (docs/ideen/web-keycloak-kanal.md
     * #6/#9) - see [AuthEvidence.replaceForSource]. [source] scopes which existing records are
     * eligible for removal (needed even when [updates] is empty - everything for that source
     * expired); each entry in [updates] still carries its own `MethodEvidence.source` for the
     * upgrade-vs-ignore decision. Used by
     * [com.example.dpop.orchestrator.journey.JourneyService.applyEvidenceUpdate], never by a
     * single completed tool outcome ([applyEvidence]).
     */
    fun applyEvidenceUpdate(authEvidenceId: UUID, updates: List<MethodEvidence>, source: String) {
        val evidence = authEvidenceRepository.findByIdOrNull(authEvidenceId)
            ?: throw IllegalArgumentException("AuthEvidence not found: $authEvidenceId")
        evidence.replaceForSource(source, updates)
        authEvidenceRepository.save(evidence)
    }

    /**
     * The one place a [ChannelSession] gets linked to its own AuthEvidence trail (starting a
     * fresh one for its account if none exists yet) and then synced via [applyEvidenceUpdate] -
     * both [ChannelSession] and this service already live in the `session` package, so the
     * linking itself belongs here, not duplicated in `orchestrator.journey.JourneyService` (which
     * only ever needs to decide WHICH JourneyEvent fires and HOW the change gets logged, journey-
     * scoped or channel-scoped - never how [channel] and its evidence get wired together).
     */
    fun attachToChannel(channel: ChannelSession, source: String, updates: List<MethodEvidence>) {
        val accountId = checkNotNull(channel.accountId) { "Evidence update without a known account" }
        if (channel.authEvidenceId == null) {
            channel.authEvidenceId = createForAccount(accountId).authEvidenceId
            sessionManagementService.updateChannelSession(channel)
        }
        applyEvidenceUpdate(checkNotNull(channel.authEvidenceId), updates, source)
    }
}
