package com.example.dpop.orchestrator.kc

import com.example.dpop.account.AccountProfile
import com.example.dpop.account.AccountService
import com.example.dpop.tool_api.PersonMasterData
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import java.time.Instant

/**
 * Pure unit test of [KeycloakAccountSyncService.syncAll]'s conflict handling, found by running the
 * review 2026-09 S-2 scenario against a real Keycloak: a Keycloak user wearing account 1's address
 * but belonging to live account 2. Aborting at account 1 would never reach account 2 - whose own
 * sync is exactly what restores its address and lifts the conflict.
 */
class KeycloakAccountSyncServiceTest : BehaviorSpec({

    fun profile(id: Long, email: String) = AccountProfile(
        accountId = id, personId = null, authenticationMethods = emptyList(),
        email = email, emailConfirmedAt = Instant.now()
    )

    given("account 1 conflicts until account 2 has been synced") {
        val accounts = mockk<AccountService>()
        every { accounts.allAccountIds() } returns listOf(1L, 2L)
        every { accounts.findAccount(1L) } returns profile(1, "max@example.com")
        every { accounts.findAccount(2L) } returns profile(2, "erika@example.com")
        every { accounts.establishedClaimValues(any(), any()) } returns emptyMap()

        val keycloak = mockk<KeycloakAdminClient>()
        var account2Synced = false
        every { keycloak.upsertUser(1L, any(), any(), any(), any(), any(), any()) } answers {
            check(account2Synced) { "Keycloak user wears the address of accountId=1 but belongs to accountId=2" }
        }
        every { keycloak.upsertUser(2L, any(), any(), any(), any(), any(), any()) } answers { account2Synced = true }
        every { keycloak.setPublicKeyCredential(any(), any(), any()) } just runs
        every { keycloak.findAllSyncedAccountIds() } returns setOf(1L, 2L)

        val keypairs = mockk<AccountKeypairService>()
        every { keypairs.keypairFor(any()) } answers { AccountKeycloakKeypair(accountId = firstArg(), publicKeyJwk = "{}") }

        val service = KeycloakAccountSyncService(accounts, mockk<PersonMasterData>(), keycloak, keypairs)

        then("the run does not abort: account 1 is retried after account 2 and succeeds") {
            service.syncAll() shouldBe KeycloakSyncResult(upserted = 2, deletedOrphans = 0, conflicts = 0)
            verify(exactly = 2) { keycloak.upsertUser(1L, any(), any(), any(), any(), any(), any()) }
        }
    }

    given("a conflict that does not resolve") {
        val accounts = mockk<AccountService>()
        every { accounts.allAccountIds() } returns listOf(1L)
        every { accounts.findAccount(1L) } returns profile(1, "max@example.com")
        every { accounts.establishedClaimValues(any(), any()) } returns emptyMap()
        val keycloak = mockk<KeycloakAdminClient>()
        every { keycloak.upsertUser(1L, any(), any(), any(), any(), any(), any()) } throws IllegalStateException("conflict")
        every { keycloak.findAllSyncedAccountIds() } returns emptySet()

        val service = KeycloakAccountSyncService(accounts, mockk<PersonMasterData>(), keycloak, mockk())

        then("it is counted and left untouched instead of failing the run") {
            service.syncAll() shouldBe KeycloakSyncResult(upserted = 0, deletedOrphans = 0, conflicts = 1)
        }
    }
})
