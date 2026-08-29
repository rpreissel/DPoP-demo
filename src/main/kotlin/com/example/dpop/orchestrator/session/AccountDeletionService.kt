package com.example.dpop.orchestrator.session

import com.example.dpop.account.AccountService
import com.example.dpop.tool_api.EnrollmentCleanup
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Hard-deletes an account and everything it exclusively owns (docs/05-api.md, Account löschen).
 * `person` (ext_stammdaten) is deliberately untouched - it is the external register the account
 * only references, not something it owns.
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
    private val authContextRepository: AuthContextRepository
) {
    private val cleanupsByType: Map<String, EnrollmentCleanup> = cleanups.associateBy { it.enrollmentType }

    fun deleteAccount(accountId: Long) {
        accountService.allEnrollmentRefs(accountId).forEach { ref ->
            cleanupsByType[ref.type]?.delete(ref)
        }

        deviceAccountLinkRepository.deleteByAccountId(accountId)

        // Every ChannelSession this account was ever bound to gets logged out server-side, on
        // every device - not just the one that asked for the deletion (docs/05-api.md, Account
        // löschen: alle Sitzungen sofort ungültig). Both the raw authContextId AND the eagerly
        // loaded `authContext` navigation property (same FK, mirrored read-only association) must
        // be cleared - leaving the latter set left Hibernate still holding the about-to-be-deleted
        // AuthContext reachable from this entity, which it flagged as an unsaved transient
        // instance once that row was actually removed below.
        channelSessionRepository.findByAccountId(accountId).forEach { session ->
            session.state = ChannelState.LOGGED_OUT
            session.authContextId = null
            session.authContext = null
            channelSessionRepository.save(session)
        }
        authContextRepository.findByAccountId(accountId).forEach { authContextRepository.delete(it) }

        accountService.deleteAccount(accountId)
    }
}
