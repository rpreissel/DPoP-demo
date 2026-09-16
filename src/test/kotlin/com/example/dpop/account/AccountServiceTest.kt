package com.example.dpop.account

import com.example.dpop.account.internal.Account
import com.example.dpop.account.internal.AccountAnchor
import com.example.dpop.account.internal.AccountAnchorRepository
import com.example.dpop.account.internal.AccountAttribute
import com.example.dpop.account.internal.AccountAttributeRepository
import com.example.dpop.account.internal.AccountRepository
import com.example.dpop.tool_api.AnchorType
import com.example.dpop.tool_api.IdentityConflictException
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.Claim
import com.example.dpop.tool_spi.TrustAnchor
import io.kotest.assertions.throwables.shouldThrow
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
import org.springframework.data.repository.findByIdOrNull

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
        val accountAnchorRepository = mockk<AccountAnchorRepository>(relaxed = true)
        val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
        val service = AccountService(accountRepository, accountAttributeRepository, accountAnchorRepository, eventPublisher)

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
        val accountAnchorRepository = mockk<AccountAnchorRepository>(relaxed = true)
        val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
        val service = AccountService(accountRepository, accountAttributeRepository, accountAnchorRepository, eventPublisher)

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
        val accountAnchorRepository = mockk<AccountAnchorRepository>(relaxed = true)
        val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
        val service = AccountService(accountRepository, accountAttributeRepository, accountAnchorRepository, eventPublisher)

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

    given("an account with an anchor-role claim") {
        val accountRepository = mockk<AccountRepository>()
        val accountAttributeRepository = mockk<AccountAttributeRepository>()
        val accountAnchorRepository = mockk<AccountAnchorRepository>(relaxed = true)
        val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
        val service = AccountService(accountRepository, accountAttributeRepository, accountAnchorRepository, eventPublisher)

        val account = Account(personId = null, createdAt = Instant.now()).apply { id = 7L }
        every { accountRepository.findByIdOrNull(7L) } returns account
        every { accountRepository.save(account) } returns account

        val savedAttributes = mutableListOf<AccountAttribute>()
        every { accountAttributeRepository.save(capture(savedAttributes)) } answers { savedAttributes.last() }
        val savedAnchors = mutableListOf<AccountAnchor>()
        every { accountAnchorRepository.save(capture(savedAnchors)) } answers { savedAnchors.last() }
        every { accountAnchorRepository.findByAccountIdAndAnchorType(7L, "email") } returns null
        every { accountAnchorRepository.findByAnchorTypeAndValue("email", any()) } returns null

        `when`("recording an email claim") {
            service.recordClaim(
                accountId = 7L,
                claim = Claim(AttributeType.EMAIL, "  Max@Example.COM ", TrustAnchor.SELF_REPORTED, AcrLevel.LOA1)
            )

            then("the claim is logged raw, the projection consolidates, its anchor materializes normalized") {
                savedAttributes.single().value shouldBe "  Max@Example.COM "
                account.email shouldBe "  Max@Example.COM "
                account.emailConfirmedAt.shouldNotBeNull()
                verify(exactly = 1) { eventPublisher.publishEvent(AccountChanged(7L)) }
                savedAnchors shouldHaveSize 1
                savedAnchors.single().accountId shouldBe 7L
                savedAnchors.single().anchorType shouldBe "email"
                savedAnchors.single().value shouldBe "max@example.com"
                savedAnchors.single().establishedAt.shouldNotBeNull()
            }
        }

        `when`("recording an anchor another account already holds") {
            every { accountAnchorRepository.findByAnchorTypeAndValue("email", "max@example.com") } returns
                AccountAnchor(anchorType = "email", value = "max@example.com", accountId = 99L, establishedAt = Instant.now())

            then("the claim is rejected instead of silently skipping the anchor (ADR-11)") {
                shouldThrow<IdentityConflictException> {
                    service.recordClaim(
                        accountId = 7L,
                        claim = Claim(AttributeType.EMAIL, "max@example.com", TrustAnchor.SELF_REPORTED, AcrLevel.LOA1)
                    )
                }
                // No second anchor, and crucially no projection write either: the account must
                // never end up claiming a value whose anchor points at account 99. Undoing the
                // already-appended log row is the surrounding transaction's job, not this
                // service's - hence the attribute count still moving here.
                savedAnchors shouldHaveSize 1
                account.email shouldBe "  Max@Example.COM "
                savedAttributes shouldHaveSize 2
            }
        }

        `when`("re-binding this account's own anchor to a new value") {
            val oldAnchor = AccountAnchor(anchorType = "email", value = "old@example.com", accountId = 7L, establishedAt = Instant.now())
            every { accountAnchorRepository.findByAnchorTypeAndValue("email", "new@example.com") } returns null
            every { accountAnchorRepository.findByAccountIdAndAnchorType(7L, "email") } returns oldAnchor

            service.recordClaim(
                accountId = 7L,
                claim = Claim(AttributeType.EMAIL, "new@example.com", TrustAnchor.SELF_REPORTED, AcrLevel.LOA1)
            )

            then("the old row goes, the anchor follows the account") {
                verify(exactly = 1) { accountAnchorRepository.delete(oldAnchor) }
                savedAnchors shouldHaveSize 2
                savedAnchors.last().value shouldBe "new@example.com"
            }
        }
    }

    given("the anchor read ports") {
        val accountRepository = mockk<AccountRepository>()
        val accountAttributeRepository = mockk<AccountAttributeRepository>()
        val accountAnchorRepository = mockk<AccountAnchorRepository>(relaxed = true)
        val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
        val service = AccountService(accountRepository, accountAttributeRepository, accountAnchorRepository, eventPublisher)

        `when`("resolving an account by anchor") {
            every { accountAnchorRepository.findByAnchorTypeAndValue("email", "max@example.com") } returns
                AccountAnchor(anchorType = "email", value = "max@example.com", accountId = 7L, establishedAt = Instant.now())

            then("the lookup runs normalized") {
                service.resolveByAnchor(AnchorType.Email, "  Max@Example.COM ") shouldBe 7L
                every { accountAnchorRepository.findByAnchorTypeAndValue("email", "other@example.com") } returns null
                service.resolveByAnchor(AnchorType.Email, "other@example.com") shouldBe null
            }
        }

        `when`("reading an account's anchor value") {
            every { accountAnchorRepository.findByAccountIdAndAnchorType(7L, "email") } returns
                AccountAnchor(anchorType = "email", value = "max@example.com", accountId = 7L, establishedAt = Instant.now())

            then("it returns the stored normalized value") {
                service.anchorValue(7L, AnchorType.Email) shouldBe "max@example.com"
                every { accountAnchorRepository.findByAccountIdAndAnchorType(8L, "email") } returns null
                service.anchorValue(8L, AnchorType.Email) shouldBe null
            }
        }
    }

    given("an account whose email gets confirmed") {
        val accountRepository = mockk<AccountRepository>()
        val accountAttributeRepository = mockk<AccountAttributeRepository>()
        val accountAnchorRepository = mockk<AccountAnchorRepository>(relaxed = true)
        val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
        val service = AccountService(accountRepository, accountAttributeRepository, accountAnchorRepository, eventPublisher)

        val account = Account(personId = null, createdAt = Instant.now()).apply { id = 7L }
        every { accountRepository.findByIdOrNull(7L) } returns account
        every { accountRepository.save(account) } returns account
        every { accountAnchorRepository.findByAccountIdAndAnchorType(7L, "email") } returns null
        every { accountAnchorRepository.findByAnchorTypeAndValue("email", any()) } returns null
        val savedAnchors = mutableListOf<AccountAnchor>()
        every { accountAnchorRepository.save(capture(savedAnchors)) } answers { savedAnchors.last() }

        `when`("confirming an email address") {
            service.confirmEmail(7L, "Max@Example.COM")

            then("the email anchor materializes normalized alongside the projection column") {
                savedAnchors shouldHaveSize 1
                savedAnchors.single().anchorType shouldBe "email"
                savedAnchors.single().value shouldBe "max@example.com"
                savedAnchors.single().accountId shouldBe 7L
                verify(exactly = 1) { eventPublisher.publishEvent(AccountChanged(7L)) }
            }
        }
    }
})