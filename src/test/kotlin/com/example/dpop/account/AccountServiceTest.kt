package com.example.dpop.account

import com.example.dpop.account.internal.Account
import com.example.dpop.account.internal.AccountAttribute
import com.example.dpop.account.internal.AccountAttributeRepository
import com.example.dpop.account.internal.AccountRepository
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.Claim
import com.example.dpop.tool_spi.TrustAnchor
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Instant
import org.springframework.context.ApplicationEventPublisher

/**
 * Pure unit test: no Spring context, repositories mocked with MockK. Covers the claims-log write
 * (docs/ideen/claims-modell-und-vertrauensanker.md Phase 1): account.personId stays the actively
 * consolidated projection, account_attribute is the append-only provenance record each
 * Completed.Identified/Completed.Enrolled claim lands in. Mocks are created fresh per `given`
 * block (not shared at spec level) so call-count assertions in one scenario never see
 * invocations from another.
 */
class AccountServiceTest : BehaviorSpec({

    given("an account with an identified person") {
        val accountRepository = mockk<AccountRepository>()
        val accountAttributeRepository = mockk<AccountAttributeRepository>()
        val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
        val service = AccountService(accountRepository, accountAttributeRepository, eventPublisher)

        `when`("recording a claim from an identifying tool") {
            val savedAttributes = mutableListOf<AccountAttribute>()
            every { accountAttributeRepository.save(capture(savedAttributes)) } answers { savedAttributes.last() }

            service.recordClaim(
                accountId = 7L,
                claim = Claim(AttributeType.PERSON_ID, "42", TrustAnchor.EXT_STAMMDATEN, AcrLevel.LOA2)
            )

            then("the claim lands in the log with all its provenance fields") {
                savedAttributes shouldHaveSize 1
                savedAttributes.single().accountId shouldBe 7L
                savedAttributes.single().attributeType shouldBe "person_id"
                savedAttributes.single().value shouldBe "42"
                savedAttributes.single().trustAnchor shouldBe "ext_stammdaten"
                savedAttributes.single().establishedLoa shouldBe "loa2"
                savedAttributes.single().establishedAt.shouldNotBeNull()
            }
        }
    }

    given("no account exists yet for a person") {
        val accountRepository = mockk<AccountRepository>()
        val accountAttributeRepository = mockk<AccountAttributeRepository>()
        val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
        val service = AccountService(accountRepository, accountAttributeRepository, eventPublisher)

        val personId = 42L
        every { accountRepository.findByPersonId(personId) } returns null
        val savedAccount = slot<Account>()
        every { accountRepository.save(capture(savedAccount)) } answers { savedAccount.captured.also { it.id = 7L } }

        `when`("finding or creating an account") {
            val profile = service.findOrCreateAccount(personId)

            then("a new account is created and its creation is published") {
                profile.personId shouldBe personId
                verify(exactly = 1) { eventPublisher.publishEvent(AccountChanged(7L)) }
            }
        }
    }

    given("an account already exists for a person") {
        val accountRepository = mockk<AccountRepository>()
        val accountAttributeRepository = mockk<AccountAttributeRepository>()
        val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
        val service = AccountService(accountRepository, accountAttributeRepository, eventPublisher)

        val personId = 42L
        val existing = Account(personId, Instant.now()).apply { id = 7L }
        every { accountRepository.findByPersonId(personId) } returns existing
        every { accountRepository.save(existing) } returns existing

        `when`("re-identifying the same person") {
            val profile = service.findOrCreateAccount(personId)

            then("the existing account is reused, not a second one, and no event fires") {
                profile.accountId shouldBe 7L
                profile.personId shouldBe personId
                verify(exactly = 0) { accountRepository.save(match { it !== existing }) }
                verify(exactly = 0) { eventPublisher.publishEvent(any()) }
            }
        }
    }
})