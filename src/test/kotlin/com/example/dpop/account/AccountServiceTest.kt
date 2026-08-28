package com.example.dpop.account

import com.example.dpop.account.internal.Account
import com.example.dpop.account.internal.AccountAttribute
import com.example.dpop.account.internal.AccountAttributeRepository
import com.example.dpop.account.internal.AccountRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Instant

/**
 * Pure unit test: no Spring context, repositories mocked with MockK. Covers
 * findOrCreateAccount's claims-log write (docs/ideen/claims-modell-und-vertrauensanker.md Phase 1)
 * - account.personId stays the actively consolidated projection, account_attribute is the log.
 * Mocks are created fresh per `given` block (not shared at spec level) so call-count assertions in
 * one scenario never see invocations from another.
 */
class AccountServiceTest : BehaviorSpec({

    given("no account exists yet for a person") {
        val accountRepository = mockk<AccountRepository>()
        val accountAttributeRepository = mockk<AccountAttributeRepository>()
        val service = AccountService(accountRepository, accountAttributeRepository)

        val personId = 42L
        every { accountRepository.findByPersonId(personId) } returns null
        val savedAccount = slot<Account>()
        every { accountRepository.save(capture(savedAccount)) } answers { savedAccount.captured.also { it.id = 7L } }
        val savedAttributes = mutableListOf<AccountAttribute>()
        every { accountAttributeRepository.save(capture(savedAttributes)) } answers { savedAttributes.last() }

        `when`("finding or creating an account via ident-fsc") {
            val profile = service.findOrCreateAccount(personId, "ident-fsc")

            then("a new account is created and the claim is logged with its trust anchor") {
                profile.personId shouldBe personId
                savedAttributes shouldHaveSize 1
                savedAttributes.single().accountId shouldBe 7L
                savedAttributes.single().attributeType shouldBe "person_id"
                savedAttributes.single().value shouldBe personId.toString()
                savedAttributes.single().trustAnchor shouldBe "ident-fsc"
            }
        }
    }

    given("an account already exists for a person (re-identification via a second method)") {
        val accountRepository = mockk<AccountRepository>()
        val accountAttributeRepository = mockk<AccountAttributeRepository>()
        val service = AccountService(accountRepository, accountAttributeRepository)

        val personId = 42L
        val existing = Account(personId, Instant.now()).apply { id = 7L }
        every { accountRepository.findByPersonId(personId) } returns existing
        every { accountRepository.save(existing) } returns existing
        val savedAttributes = mutableListOf<AccountAttribute>()
        every { accountAttributeRepository.save(capture(savedAttributes)) } answers { savedAttributes.last() }

        `when`("re-identifying via ident-eid") {
            val profile = service.findOrCreateAccount(personId, "ident-eid")

            then("the existing account is reused, not a second one, but a second claim is logged") {
                profile.accountId shouldBe 7L
                profile.personId shouldBe personId
                verify(exactly = 0) { accountRepository.save(match { it !== existing }) }
                savedAttributes shouldHaveSize 1
                savedAttributes.single().trustAnchor shouldBe "ident-eid"
                savedAttributes.single().value shouldBe personId.toString()
            }
        }
    }
})
