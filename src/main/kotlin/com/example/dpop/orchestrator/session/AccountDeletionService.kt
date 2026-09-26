package com.example.dpop.orchestrator.session

import com.example.dpop.account.AccountService
import com.example.dpop.account.RetractionAnchor
import com.example.dpop.orchestrator.journeylog.JourneyLogRepository
import com.example.dpop.tool_api.EnrollmentCleanup
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Hard-deletes an account and everything it exclusively owns (docs/05-api.md, Account löschen).
 * `ext_personenverzeichnis.person` is deliberately untouched - it is the external register the account
 * only references, not something it owns. The account module's own child tables (anchors,
 * methods, claim and identification logs) are not listed below either: they cascade with the
 * final `DELETE FROM account.account`. Everything that hangs off no such key has to be named
 * explicitly here - see the journey log and throttle cleanup in [deleteAccount].
 *
 * Orchestrates across module boundaries without ever depending on a method module by name: the
 * cross-module credential cleanup dispatches through [EnrollmentCleanup], the same SPI pattern
 * [com.example.dpop.orchestrator.tool.ToolHandlerRegistry] already uses to aggregate every
 * module's [com.example.dpop.tool_spi.ToolDescriptor] - Spring collects every implementing bean
 * regardless of which module declares it.
 */
@Service
@Transactional
class AccountDeletionService(
    private val accountService: AccountService,
    cleanups: List<EnrollmentCleanup>,
    private val deviceAccountLinkRepository: DeviceAccountLinkRepository,
    private val channelSessionRepository: ChannelSessionRepository,
    private val authContextRepository: AuthContextRepository,
    private val authEvidenceRepository: AuthEvidenceRepository,
    private val journeyLogRepository: JourneyLogRepository,
    private val attemptThrottleRepository: AttemptThrottleRepository
) {
    private val cleanupsByType: Map<String, EnrollmentCleanup> = cleanups.associateBy { it.enrollmentType }

    /** The module's credential row goes - unless another account's method still points at it. */
    private fun deleteCredential(accountId: Long, ref: com.example.dpop.tool_spi.EnrollmentRef) {
        if (accountService.isEnrollmentSharedWithOtherAccount(accountId, ref)) return
        cleanupsByType[ref.type]?.delete(ref)
    }

    fun deleteAccount(accountId: Long) {
        accountService.allEnrollmentRefs(accountId).forEach { ref -> deleteCredential(accountId, ref) }

        deviceAccountLinkRepository.deleteByAccountId(accountId)

        // Every ChannelSession this account was ever bound to gets logged out server-side, on
        // every device - not just the one that asked for the deletion (docs/05-api.md, Account
        // löschen: alle Sitzungen sofort ungültig). Both the raw id AND the eagerly loaded
        // navigation property (same FK, mirrored read-only association) must be cleared for BOTH
        // authContextId/authContext and authEvidenceId/authEvidence - leaving either navigation
        // property set left Hibernate still holding the about-to-be-deleted row reachable from
        // this entity, which it flagged as an unsaved transient instance once that row was
        // actually removed below.
        val channelSessions = channelSessionRepository.findByAccountId(accountId)
        channelSessions.forEach { session ->
            session.state = ChannelState.LOGGED_OUT
            session.authContextId = null
            session.authContext = null
            session.authEvidenceId = null
            session.authEvidence = null
            channelSessionRepository.save(session)
        }
        authContextRepository.findByAccountId(accountId).forEach { authContextRepository.delete(it) }
        authEvidenceRepository.findByAccountId(accountId).forEach { authEvidenceRepository.delete(it) }

        accountService.deleteAccount(accountId)

        // Strictly LAST, and deliberately so. These are bulk statements, and a bulk statement over
        // `orchestrator.journey_log` overlaps the query space of the entries the running journey has itself
        // just written but not yet flushed - which forces Hibernate to auto-flush the whole
        // persistence context mid-request. That early flush bumps the version of every dirty
        // session entity, and any caller still holding the pre-flush copy then fails its own
        // optimistic-lock check (surfacing as a spurious 409 CONCURRENT_MODIFICATION on the very
        // request that asked for the deletion). After the account row is gone the context has
        // been flushed anyway, so here the same statements are free of that interaction.
        //
        // The journey log holds identity-adjacent data in its `detail` JSON and hangs off no
        // foreign key that could cascade, so erasure has to name it explicitly - by account AND
        // by the account's channel sessions, because entries written before the channel resolved
        // an account carry a null accountId
        // (JourneyLogRepository.deleteByAccountIdOrChannelSessionIdIn, which also explains why
        // that has to stay a single statement).
        journeyLogRepository.deleteByAccountIdOrChannelSessionIdIn(
            accountId,
            channelSessions.mapNotNull { it.channelSessionId }.ifEmpty { listOf(NO_CHANNEL_SESSION) }
        )
        // Account-keyed throttle counters only - the other scopes are not this account's to clear,
        // and clearing them would make deletion a way to reset someone's budget
        // (AttemptThrottleRepository.deleteBySubjectAndScopeIn).
        attemptThrottleRepository.deleteBySubjectAndScopeIn(
            accountId.toString(),
            listOf(ThrottleScope.ACCOUNT, ThrottleScope.ACCOUNT_SEND)
        )
    }

    /**
     * Revokes ONE authentication method instance, not the whole account - reuses the same
     * [cleanupsByType] dispatch as [deleteAccount], scoped to a single [methodInstanceId]. Used
     * outside account deletion too: when a device is rebound to a different account
     * (`JourneyActionExecutor.perform`, `Action.LinkDevice`), the previous account's device-bound
     * credential for that exact physical key is revoked the same way - it must not keep matching
     * once that key is no longer theirs (docs/09-dpop.md).
     */
    fun revokeMethod(accountId: Long, methodInstanceId: String) {
        accountService.enrollmentRefFor(accountId, methodInstanceId)?.let { ref -> deleteCredential(accountId, ref) }
        // The credential row is gone, so whatever only IT backed stops being a valid claim
        // (ADR-12). Account-owned facts this method happened to assert along the way survive -
        // the rule lives in retractClaimsOf, not here.
        accountService.retractClaimsOf(
            accountId,
            methodInstanceId,
            RetractionAnchor.ACCOUNT_MANAGEMENT,
            reason = "method instance revoked"
        )
        accountService.deactivateAuthenticationMethod(accountId, methodInstanceId)
    }

    private companion object {
        /**
         * Placeholder for "this account had no channel session at all" - a JPQL `in` clause
         * rejects an empty collection, and the all-zero UUID is never generated for a real
         * channel session.
         */
        private val NO_CHANNEL_SESSION: java.util.UUID = java.util.UUID(0L, 0L)
    }
}
