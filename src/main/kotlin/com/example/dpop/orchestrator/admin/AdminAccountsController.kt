package com.example.dpop.orchestrator.admin

import com.example.dpop.account.AccountService
import com.example.dpop.orchestrator.kc.LoginTheme
import com.example.dpop.orchestrator.kernel.FeatureFlags
import com.example.dpop.orchestrator.session.AccountDeletionService
import com.example.dpop.orchestrator.session.FeatureFlagService
import com.example.dpop.orchestrator.tool.ToolAvailabilityService
import com.example.dpop.tool_api.PersonDirectory
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import com.example.dpop.demo_seed.DemoAccountSeed
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class AdminAccountView(
    val accountId: Long,
    val personId: String?,
    val displayName: String?,
    val email: String?,
    /** Active method names, one per instance (several devices show up several times). */
    val methods: List<String>
)

data class DemoResetResult(
    val deletedAccounts: Int,
    @field:Schema(description = "Demo accounts created again right after the reset - 0 without the keycloak profile, which has none.")
    val seededAccounts: Int
)

/**
 * Accounts from the operator's side: list, delete one, or put the whole demo back to its start.
 * Deletion goes through [AccountDeletionService], the same path an account's own DELETE_ACCOUNT
 * journey takes - no second, shorter way that could leave credentials of a method module behind.
 */
@RestController
@RequestMapping(ADMIN_API)
@Tag(name = "Admin: accounts", description = "List and delete accounts, reset the demo")
class AdminAccountsController(
    private val accountService: AccountService,
    private val accountDeletionService: AccountDeletionService,
    private val personDirectory: PersonDirectory,
    private val toolAvailabilityService: ToolAvailabilityService,
    private val featureFlagService: FeatureFlagService,
    private val demoAccountSeed: ObjectProvider<DemoAccountSeed>,
    private val loginThemeSwitch: ObjectProvider<LoginThemeSwitch>,
) {

    @GetMapping("accounts")
    @Operation(summary = "All accounts")
    fun accounts(): List<AdminAccountView> =
        accountService.allAccountIds().sorted().mapNotNull { accountService.findAccount(it) }.map { profile ->
            AdminAccountView(
                accountId = profile.accountId,
                personId = profile.personId,
                displayName = profile.personId?.let { personDirectory.displayName(it) },
                email = profile.email,
                methods = profile.activeAuthenticationMethods.map { it.method }
            )
        }

    @DeleteMapping("accounts/{accountId}")
    @Operation(summary = "Delete one account", description = "Same cleanup as the account's own deletion journey; its channels end up logged out.")
    fun delete(@PathVariable accountId: Long): ResponseEntity<Void> {
        if (accountService.findAccount(accountId) == null) return ResponseEntity.notFound().build()
        accountDeletionService.deleteAccount(accountId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("demo-reset")
    @Operation(
        summary = "Put the demo back to its start",
        description = "Deletes every account, restores the preset tool order and locks per channel, the ident-first registration order and the FreeMarker login theme, then creates the demo accounts again (keycloak profile). " +
            "The person register (/mock-personenverzeichnis) is a foreign system and stays as it is."
    )
    fun reset(): DemoResetResult {
        val accountIds = accountService.allAccountIds()
        accountIds.forEach { accountDeletionService.deleteAccount(it) }
        toolAvailabilityService.applyDefaults()
        featureFlagService.setEnabled(FeatureFlags.REGISTER_ENROLL_FIRST, false)
        loginThemeSwitch.ifAvailable?.switchTo(LoginTheme.FREEMARKER)
        // Back to the start means the demo accounts too, not an empty system until the next boot.
        val seeded = demoAccountSeed.ifAvailable?.seed() ?: 0
        return DemoResetResult(deletedAccounts = accountIds.size, seededAccounts = seeded)
    }
}
