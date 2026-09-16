package com.example.dpop.account

import com.example.dpop.account.internal.Account
import com.example.dpop.account.internal.AccountAnchor
import com.example.dpop.account.internal.AccountAnchorRepository
import com.example.dpop.account.internal.AccountAttribute
import com.example.dpop.account.internal.AccountAttributeRepository
import com.example.dpop.account.internal.AccountRaceSafeCreator
import com.example.dpop.account.internal.AccountRepository
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_api.IdentityConflictException
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.Claim
import com.example.dpop.tool_spi.ClaimSource
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
 * (docs/ideen/claims-modell-und-vertrauensanker.md Phase 1; docs/ideen/account-attribute-und-
 * trust-vereinheitlichen.md Paket 4): account_attribute is the append-only provenance record
 * every Completed.Identified/Completed.Enrolled claim lands in, account.personId and
 * account.email are BOTH actively consolidated OwnedColumn projections with their own anchor -
 * same technical path, `AttributeType.allowsAnchorReplacement` is what actually differs
 * (personId is immutable after first binding, email is re-provable). Mocks are created fresh per
 * `given` block (not shared at spec level) so call-count assertions in one scenario never see
 * invocations from another.
 */
class AccountServiceTest : BehaviorSpec({

    given("an unidentified account receiving a person_id claim") {
        val accountRepository = mockk<AccountRepository>()
        val accountAttributeRepository = mockk<AccountAttributeRepository>()
        val accountAnchorRepository = mockk<AccountAnchorRepository>(relaxed = true)
        val accountRaceSafeCreator = mockk<AccountRaceSafeCreator>(relaxed = true)
        val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
        val service = AccountService(accountRepository, accountAttributeRepository, accountAnchorRepository, accountRaceSafeCreator, eventPublisher)

        val account = Account(personId = null, createdAt = Instant.now()).apply { id = 7L }
        every { accountRepository.findByIdOrNull(7L) } returns account
        every { accountRepository.save(account) } returns account
        every { accountAnchorRepository.findByAccountIdAndAttributeType(7L, AttributeType.PERSON_ID) } returns null
        every { accountAnchorRepository.findByAttributeTypeAndValue(AttributeType.PERSON_ID, any()) } returns null

        `when`("recording a claim from an identifying tool") {
            val savedAttributes = mutableListOf<AccountAttribute>()
            every { accountAttributeRepository.save(capture(savedAttributes)) } answers { savedAttributes.last() }
            val savedAnchors = mutableListOf<AccountAnchor>()
            every { accountAnchorRepository.save(capture(savedAnchors)) } answers { savedAnchors.last() }

            service.recordClaim(
                accountId = 7L,
                claim = Claim(AttributeType.PERSON_ID, "42", ClaimSource.EXT_STAMMDATEN, AcrLevel.LOA2)
            )

            then("the claim lands in the log, the projection and anchor consolidate (PERSON_ID is OwnedColumn)") {
                savedAttributes shouldHaveSize 1
                savedAttributes.single().accountId shouldBe 7L
                savedAttributes.single().attributeType shouldBe AttributeType.PERSON_ID
                savedAttributes.single().value shouldBe "42"
                savedAttributes.single().trustAnchor shouldBe "ext_stammdaten"
                savedAttributes.single().establishedLoa shouldBe "loa2"
                savedAttributes.single().establishedAt.shouldNotBeNull()
                account.personId shouldBe 42L
                savedAnchors shouldHaveSize 1
                savedAnchors.single().attributeType shouldBe AttributeType.PERSON_ID
                savedAnchors.single().value shouldBe "42"
                savedAnchors.single().accountId shouldBe 7L
                verify(exactly = 1) { eventPublisher.publishEvent(AccountChanged(7L)) }
            }
        }
    }

    given("an account already bound to a person_id") {
        val accountRepository = mockk<AccountRepository>()
        val accountAttributeRepository = mockk<AccountAttributeRepository>(relaxed = true)
        val accountAnchorRepository = mockk<AccountAnchorRepository>(relaxed = true)
        val accountRaceSafeCreator = mockk<AccountRaceSafeCreator>(relaxed = true)
        val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
        val service = AccountService(accountRepository, accountAttributeRepository, accountAnchorRepository, accountRaceSafeCreator, eventPublisher)

        val account = Account(personId = 42L, createdAt = Instant.now()).apply { id = 7L }
        every { accountRepository.findByIdOrNull(7L) } returns account
        every { accountRepository.save(account) } returns account
        every { accountAttributeRepository.save(any()) } answers { firstArg() }
        val existingAnchor = AccountAnchor(attributeType = AttributeType.PERSON_ID, value = "42", accountId = 7L, establishedAt = Instant.now())

        `when`("re-asserting the same person_id") {
            every { accountAnchorRepository.findByAttributeTypeAndValue(AttributeType.PERSON_ID, "42") } returns existingAnchor

            then("it is idempotent - no new anchor row, no rejection") {
                service.recordClaim(7L, Claim(AttributeType.PERSON_ID, "42", ClaimSource.EXT_STAMMDATEN))
                account.personId shouldBe 42L
                verify(exactly = 0) { accountAnchorRepository.save(any()) }
                verify(exactly = 0) { accountAnchorRepository.delete(any()) }
            }
        }

        `when`("asserting a DIFFERENT person_id for the same account") {
            every { accountAnchorRepository.findByAttributeTypeAndValue(AttributeType.PERSON_ID, "99") } returns null
            every { accountAnchorRepository.findByAccountIdAndAttributeType(7L, AttributeType.PERSON_ID) } returns existingAnchor

            then("it is rejected - person_id is immutable after first binding (docs/ideen/account-attribute-und-trust-vereinheitlichen.md)") {
                shouldThrow<IdentityConflictException> {
                    service.recordClaim(7L, Claim(AttributeType.PERSON_ID, "99", ClaimSource.EXT_STAMMDATEN))
                }
                account.personId shouldBe 42L
                verify(exactly = 0) { accountAnchorRepository.delete(any()) }
                verify(exactly = 0) { accountAnchorRepository.save(any()) }
            }
        }
    }

    given("no account exists yet for a person") {
        val accountRepository = mockk<AccountRepository>()
        val accountAttributeRepository = mockk<AccountAttributeRepository>()
        val accountAnchorRepository = mockk<AccountAnchorRepository>(relaxed = true)
        val accountRaceSafeCreator = mockk<AccountRaceSafeCreator>(relaxed = true)
        val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
        val service = AccountService(accountRepository, accountAttributeRepository, accountAnchorRepository, accountRaceSafeCreator, eventPublisher)

        val personId = 42L
        val created = Account(personId, Instant.now()).apply { id = 7L }
        every { accountRepository.findByPersonId(personId) } returnsMany listOf(null, created)
        every { accountRaceSafeCreator.createIfAbsent(personId) } returns true

        `when`("finding or creating an account") {
            val profile = service.findOrCreateAccount(personId)

            then("a new account is created and its creation is published") {
                profile.personId shouldBe personId
                verify(exactly = 1) { eventPublisher.publishEvent(AccountChanged(7L)) }
            }
        }
    }

    given("a concurrent step-up channel wins the race to create the account first") {
        val accountRepository = mockk<AccountRepository>()
        val accountAttributeRepository = mockk<AccountAttributeRepository>()
        val accountAnchorRepository = mockk<AccountAnchorRepository>(relaxed = true)
        val accountRaceSafeCreator = mockk<AccountRaceSafeCreator>(relaxed = true)
        val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
        val service = AccountService(accountRepository, accountAttributeRepository, accountAnchorRepository, accountRaceSafeCreator, eventPublisher)

        val personId = 42L
        val winnersAccount = Account(personId, Instant.now()).apply { id = 7L }
        // The initial lookup still sees nothing (this caller lost the race); createIfAbsent hits
        // ux_account_person_id (V32) and reports "already exists" rather than throwing.
        every { accountRepository.findByPersonId(personId) } returnsMany listOf(null, winnersAccount)
        every { accountRaceSafeCreator.createIfAbsent(personId) } returns false

        `when`("finding or creating an account") {
            val profile = service.findOrCreateAccount(personId)

            then("the winner's account is returned instead of a constraint-violation error, no duplicate event") {
                profile.accountId shouldBe 7L
                profile.personId shouldBe personId
                verify(exactly = 0) { eventPublisher.publishEvent(any()) }
            }
        }
    }

    given("an account already exists for a person") {
        val accountRepository = mockk<AccountRepository>()
        val accountAttributeRepository = mockk<AccountAttributeRepository>()
        val accountAnchorRepository = mockk<AccountAnchorRepository>(relaxed = true)
        val accountRaceSafeCreator = mockk<AccountRaceSafeCreator>(relaxed = true)
        val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
        val service = AccountService(accountRepository, accountAttributeRepository, accountAnchorRepository, accountRaceSafeCreator, eventPublisher)

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
        val accountRaceSafeCreator = mockk<AccountRaceSafeCreator>(relaxed = true)
        val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
        val service = AccountService(accountRepository, accountAttributeRepository, accountAnchorRepository, accountRaceSafeCreator, eventPublisher)

        val account = Account(personId = null, createdAt = Instant.now()).apply { id = 7L }
        every { accountRepository.findByIdOrNull(7L) } returns account
        every { accountRepository.save(account) } returns account

        val savedAttributes = mutableListOf<AccountAttribute>()
        every { accountAttributeRepository.save(capture(savedAttributes)) } answers { savedAttributes.last() }
        val savedAnchors = mutableListOf<AccountAnchor>()
        every { accountAnchorRepository.save(capture(savedAnchors)) } answers { savedAnchors.last() }
        every { accountAnchorRepository.findByAccountIdAndAttributeType(7L, AttributeType.EMAIL) } returns null
        every { accountAnchorRepository.findByAttributeTypeAndValue(AttributeType.EMAIL, any()) } returns null

        `when`("recording an email claim") {
            service.recordClaim(
                accountId = 7L,
                claim = Claim(AttributeType.EMAIL, "  Max@Example.COM ", ClaimSource.SELF_REPORTED, AcrLevel.LOA1)
            )

            then("the claim is logged raw, the projection consolidates, its anchor materializes normalized") {
                savedAttributes.single().value shouldBe "  Max@Example.COM "
                account.email shouldBe "  Max@Example.COM "
                account.emailConfirmedAt.shouldNotBeNull()
                verify(exactly = 1) { eventPublisher.publishEvent(AccountChanged(7L)) }
                savedAnchors shouldHaveSize 1
                savedAnchors.single().accountId shouldBe 7L
                savedAnchors.single().attributeType shouldBe AttributeType.EMAIL
                savedAnchors.single().value shouldBe "max@example.com"
                savedAnchors.single().establishedAt.shouldNotBeNull()
            }
        }

        `when`("recording an anchor another account already holds") {
            every { accountAnchorRepository.findByAttributeTypeAndValue(AttributeType.EMAIL, "max@example.com") } returns
                AccountAnchor(attributeType = AttributeType.EMAIL, value = "max@example.com", accountId = 99L, establishedAt = Instant.now())

            then("the claim is rejected instead of silently skipping the anchor (ADR-11)") {
                shouldThrow<IdentityConflictException> {
                    service.recordClaim(
                        accountId = 7L,
                        claim = Claim(AttributeType.EMAIL, "max@example.com", ClaimSource.SELF_REPORTED, AcrLevel.LOA1)
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
            val oldAnchor = AccountAnchor(attributeType = AttributeType.EMAIL, value = "old@example.com", accountId = 7L, establishedAt = Instant.now())
            every { accountAnchorRepository.findByAttributeTypeAndValue(AttributeType.EMAIL, "new@example.com") } returns null
            every { accountAnchorRepository.findByAccountIdAndAttributeType(7L, AttributeType.EMAIL) } returns oldAnchor

            service.recordClaim(
                accountId = 7L,
                claim = Claim(AttributeType.EMAIL, "new@example.com", ClaimSource.SELF_REPORTED, AcrLevel.LOA1)
            )

            then("the existing row is UPDATED in place, not deleted and re-inserted (real unique-constraint ordering, docs/ideen/account-attribute-und-trust-vereinheitlichen.md)") {
                verify(exactly = 0) { accountAnchorRepository.delete(any()) }
                // savedAnchors already holds the first `when`'s save (shared given-block state);
                // this rebind adds exactly one more save - of the SAME oldAnchor instance,
                // mutated, not a fresh AccountAnchor.
                savedAnchors.last() shouldBe oldAnchor
                oldAnchor.value shouldBe "new@example.com"
            }
        }
    }

    given("the anchor read ports") {
        val accountRepository = mockk<AccountRepository>()
        val accountAttributeRepository = mockk<AccountAttributeRepository>()
        val accountAnchorRepository = mockk<AccountAnchorRepository>(relaxed = true)
        val accountRaceSafeCreator = mockk<AccountRaceSafeCreator>(relaxed = true)
        val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
        val service = AccountService(accountRepository, accountAttributeRepository, accountAnchorRepository, accountRaceSafeCreator, eventPublisher)

        `when`("resolving an account by anchor") {
            every { accountAnchorRepository.findByAttributeTypeAndValue(AttributeType.EMAIL, "max@example.com") } returns
                AccountAnchor(attributeType = AttributeType.EMAIL, value = "max@example.com", accountId = 7L, establishedAt = Instant.now())

            then("the lookup runs normalized") {
                service.resolveByAnchor(AttributeType.EMAIL, "  Max@Example.COM ") shouldBe 7L
                every { accountAnchorRepository.findByAttributeTypeAndValue(AttributeType.EMAIL, "other@example.com") } returns null
                service.resolveByAnchor(AttributeType.EMAIL, "other@example.com") shouldBe null
            }
        }

        `when`("reading an account's anchor value") {
            every { accountAnchorRepository.findByAccountIdAndAttributeType(7L, AttributeType.EMAIL) } returns
                AccountAnchor(attributeType = AttributeType.EMAIL, value = "max@example.com", accountId = 7L, establishedAt = Instant.now())

            then("it returns the stored normalized value") {
                service.anchorValue(7L, AttributeType.EMAIL) shouldBe "max@example.com"
                every { accountAnchorRepository.findByAccountIdAndAttributeType(8L, AttributeType.EMAIL) } returns null
                service.anchorValue(8L, AttributeType.EMAIL) shouldBe null
            }
        }
    }
})
