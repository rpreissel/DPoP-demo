package com.example.dpop.orchestrator.kc

import com.example.dpop.account.AccountChanged
import com.example.dpop.account.AccountService
import com.example.dpop.tool_spi.AttributeType
import io.kotest.core.spec.style.BehaviorSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

/**
 * The Keycloak sync only makes the round trip when a change can concern what it mirrors
 * (ADR-34): a change the account could name, touching nothing Keycloak carries, is skipped.
 */
class KeycloakSyncFilterTest : BehaviorSpec({

    fun fixture(): Pair<KeycloakAccountSyncListener, AccountService> {
        val accountService = mockk<AccountService>()
        every { accountService.findAccount(any()) } returns null
        return KeycloakAccountSyncListener(accountService, mockk(), mockk(), mockk(), mockk()) to accountService
    }

    given("an AccountChanged naming what changed") {
        then("nothing Keycloak mirrors - no sync") {
            val (listener, accounts) = fixture()
            listener.onAccountChanged(AccountChanged(1L, setOf(AttributeType.EMAIL)))
            verify(exactly = 0) { accounts.findAccount(any()) }
        }

        then("an address, the KVNR or the Versicherungsnummer - sync") {
            listOf(AttributeType.PLZ, AttributeType.KVNR, AttributeType.VERSNR).forEach { type ->
                val (listener, accounts) = fixture()
                listener.onAccountChanged(AccountChanged(1L, setOf(type)))
                verify(exactly = 1) { accounts.findAccount(1L) }
            }
        }
    }

    given("an AccountChanged without a named cause") {
        then("always syncs") {
            val (listener, accounts) = fixture()
            listener.onAccountChanged(AccountChanged(1L))
            verify(exactly = 1) { accounts.findAccount(1L) }
        }
    }
})
