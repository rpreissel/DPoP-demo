package com.example.dpop.orchestrator.admin

import com.example.dpop.account.AccountService
import com.example.dpop.orchestrator.journeylog.JourneyLogEntryView
import com.example.dpop.orchestrator.journeylog.JourneyLogService
import com.example.dpop.tool_api.PersonDirectory
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** An account the log can be filtered by, named as far as the demo may say: the register's display name (`PersonDirectory.displayName`). */
data class AdminAccountLabel(val accountId: Long, val displayName: String?)

data class AdminJourneyLogResponse(val entries: List<JourneyLogEntryView>, val accounts: List<AdminAccountLabel>)

/**
 * The journey log across every account and device - what the per-device and per-account views
 * (docs/05-api.md) deliberately cannot show. An operator view, so under [ADMIN_API] and its login.
 */
@RestController
@RequestMapping("$ADMIN_API/journey-log")
@Tag(name = "Admin: journey log", description = "Journey log across all accounts and channels")
class AdminJourneyLogController(
    private val journeyLogService: JourneyLogService,
    private val accountService: AccountService,
    private val personDirectory: PersonDirectory,
) {

    @GetMapping
    @Operation(summary = "Newest journey-log entries across all accounts", description = "At most `limit` entries (1-2000), newest first.")
    fun recent(@RequestParam(defaultValue = "500") limit: Int): AdminJourneyLogResponse {
        val log = journeyLogService.getRecent(limit.coerceIn(1, MAX_LIMIT))
        // Every existing account, not just those with entries in this page: a seeded account that
        // never ran a journey is still someone the operator may want to pick (and see "no entries"
        // for). Ids only found in the log belong to since-deleted accounts and keep their number.
        val accountIds = (accountService.allAccountIds() + log.entries.mapNotNull { it.accountId }).distinct().sorted()
        val accounts = accountIds.map { accountId ->
            AdminAccountLabel(accountId, accountService.findAccount(accountId)?.personId?.let { personDirectory.displayName(it) })
        }
        return AdminJourneyLogResponse(log.entries, accounts)
    }

    private companion object {
        const val MAX_LIMIT = 2000
    }
}
