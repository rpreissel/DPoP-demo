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
    private val authContextRepository: AuthContextRepository,
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
        invalidateCachedTokens(authEvidenceId)
    }

    /**
     * A sync of [source]'s complete, currently-valid set (docs/05-api.md
     * Abschnitt 3) - see [AuthEvidence.replaceForSource]. [source] scopes which existing records are
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
        invalidateCachedTokens(authEvidenceId)
    }

    /**
     * A step-up (docs/07-betrieb.md #2) mutates the SAME [AuthEvidence] row an already-minted
     * AccessToken was baked from ([TokenService]/[KcTokenProvider] both cache purely by time,
     * `authContext.tokenExpiresAt`) - without this, a client polling `.../token` right after a
     * step-up could keep getting back the pre-step-up token/claims for up to the token's own TTL,
     * never noticing the account's ACR/AMR actually changed. Clearing the cache (not re-minting
     * here) keeps this service free of [TokenService]/[TokenProvider] as a dependency - the next
     * `.../token` call mints fresh evidence-included claims itself, same as first issuance.
     *
     * Also clears the RefreshToken, not just the AccessToken: [KcTokenProvider]'s cheap
     * `refresh_token`-grant path (DPoP-demo-xso, ADR-9) never re-signs an assertion, so it would
     * otherwise keep renewing a Keycloak session whose acr/amr session notes still reflect the
     * PRE-step-up evidence forever. A leftover RefreshToken surviving a step-up is exactly the
     * "same session, stale claims" bug this whole invalidation exists to prevent - forcing the
     * next call through a fresh, evidence-carrying assertion is the fix, not an incidental side
     * effect.
     */
    private fun invalidateCachedTokens(authEvidenceId: UUID) {
        authContextRepository.findByAuthEvidenceId(authEvidenceId).forEach { authContext ->
            authContext.tokenHandle = null
            authContext.tokenExpiresAt = null
            authContext.refreshTokenHandle = null
            authContext.refreshExpiresAt = null
            authContextRepository.save(authContext)
        }
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
