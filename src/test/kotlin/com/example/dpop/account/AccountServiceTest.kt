package com.example.dpop.account

import com.example.dpop.account.internal.Account
import com.example.dpop.account.internal.AccountAnchor
import com.example.dpop.account.internal.AccountAnchorRepository
import com.example.dpop.account.internal.AccountAttribute
import com.example.dpop.account.internal.AccountAttributeRepository
import com.example.dpop.account.internal.AccountRepository
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_api.IdentityConflictException
import com.example.dpop.tool_api.PersonDirectory
import com.example.dpop.tool_api.resolveAccountByEmail
import com.example.dpop.tool_api.resolveAccountByPersonId
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.Claim
import com.example.dpop.tool_spi.ClaimSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.Called
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Instant
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.repository.findByIdOrNull

/**
 * Pure unit test: no Spring context, repositories mocked with MockK. Covers the claims-log write
 * (docs/ideen/claims-modell-und-vertrauensanker.md Phase 1; docs/ideen/account-attribute-und-
 * trust-vereinheitlichen.md Paket 4): account.attribute is the append-only provenance record
 * every Completed.Identified/Completed.Enrolled claim lands in; PERSON_ID and EMAIL are both
 * consolidated into their account.anchor - same technical path, `AttributeType.allowsAnchorReplacement` is what actually differs
 * (personId is immutable after first binding, email is re-provable). Mocks are created fresh per
 * `given` block (not shared at spec level) so call-count assertions in one scenario never see
 * invocations from another.
 */
class AccountServiceTest : BehaviorSpec({

    given("an unidentified account receiving a person_id claim") {
        val accountRepository = mockk<AccountRepository>()
        val accountAttributeRepository = mockk<AccountAttributeRepository>()
        val accountAnchorRepository = mockk<AccountAnchorRepository>(relaxed = true)
        val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
        val service = AccountService(accountRepository, accountAttributeRepository, accountAnchorRepository, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), eventPublisher)

        val account = Account(createdAt = Instant.now()).apply { id = 7L }
        every { accountRepository.findByIdOrNull(7L) } returns account
        every { accountRepository.findForUpdate(7L) } returns account
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

            then("the claim lands in the log and its anchor consolidates") {
                savedAttributes shouldHaveSize 1
                savedAttributes.single().accountId shouldBe 7L
                savedAttributes.single().attributeType shouldBe AttributeType.PERSON_ID
                savedAttributes.single().value shouldBe "42"
                savedAttributes.single().claimSource shouldBe "ext_stammdaten"
                savedAttributes.single().establishedLoa shouldBe "loa2"
                savedAttributes.single().establishedAt.shouldNotBeNull()
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
        val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
        val service = AccountService(accountRepository, accountAttributeRepository, accountAnchorRepository, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), eventPublisher)

        val account = Account(createdAt = Instant.now()).apply { id = 7L }
        every { accountRepository.findByIdOrNull(7L) } returns account
        every { accountRepository.findForUpdate(7L) } returns account
        every { accountAttributeRepository.save(any()) } answers { firstArg() }
        val existingAnchor = AccountAnchor(attributeType = AttributeType.PERSON_ID, value = "42", accountId = 7L, establishedAt = Instant.now())

        `when`("re-asserting the same person_id") {
            every { accountAnchorRepository.findByAttributeTypeAndValue(AttributeType.PERSON_ID, "42") } returns existingAnchor

            then("it is idempotent - no new anchor row, no rejection") {
                service.recordClaim(7L, Claim(AttributeType.PERSON_ID, "42", ClaimSource.EXT_STAMMDATEN))
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
                verify(exactly = 0) { accountAnchorRepository.delete(any()) }
                verify(exactly = 0) { accountAnchorRepository.save(any()) }
            }
        }
    }

    given("no account exists yet") {
        val accountRepository = mockk<AccountRepository>()
        val accountAttributeRepository = mockk<AccountAttributeRepository>()
        val accountAnchorRepository = mockk<AccountAnchorRepository>(relaxed = true)
        val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
        val service = AccountService(accountRepository, accountAttributeRepository, accountAnchorRepository, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), eventPublisher)

        every { accountRepository.save(any()) } answers { firstArg<Account>().apply { id = 7L } }

        `when`("creating an account before accepting its claims") {
            val profile = service.createUnidentifiedAccount()

            then("the new account has no direct person binding") {
                profile.personId shouldBe null
                verify(exactly = 1) { accountRepository.save(any()) }
                verify(exactly = 1) { eventPublisher.publishEvent(AccountChanged(7L)) }
            }
        }
    }

    given("an account with an anchor-role claim") {
        val accountRepository = mockk<AccountRepository>()
        val accountAttributeRepository = mockk<AccountAttributeRepository>()
        val accountAnchorRepository = mockk<AccountAnchorRepository>(relaxed = true)
        val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
        val service = AccountService(accountRepository, accountAttributeRepository, accountAnchorRepository, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), eventPublisher)

        val account = Account(createdAt = Instant.now()).apply { id = 7L }
        every { accountRepository.findByIdOrNull(7L) } returns account
        every { accountRepository.findForUpdate(7L) } returns account

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

            then("the claim is logged raw, its anchor materializes normalized") {
                savedAttributes.single().value shouldBe "  Max@Example.COM "
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
                // No second anchor. Undoing the already-appended log row is the surrounding
                // transaction's job, not this service's - hence the attribute count still moving here.
                savedAnchors shouldHaveSize 1
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

    given("ID lookups on the concrete account service") {
        then("they do not load an account or build a profile") {
            val accounts = mockk<AccountRepository>()
            val anchors = mockk<AccountAnchorRepository>()
            val service = AccountService(accounts, mockk(), anchors, mockk(), mockk(), mockk(), mockk())
            every { anchors.findByAttributeTypeAndValue(AttributeType.EMAIL, "max@example.com") } returns
                AccountAnchor(attributeType = AttributeType.EMAIL, value = "max@example.com", accountId = 7L, establishedAt = Instant.now())
            every { anchors.findByAttributeTypeAndValue(AttributeType.PERSON_ID, "42") } returns
                AccountAnchor(attributeType = AttributeType.PERSON_ID, value = "42", accountId = 7L, establishedAt = Instant.now())
            service.resolveAccountByEmail("  Max@Example.COM ") shouldBe 7L
            service.resolveAccountByPersonId(42L) shouldBe 7L
            verify { accounts wasNot Called }
        }
    }

    given("the anchor read ports") {
        val accountRepository = mockk<AccountRepository>()
        val accountAttributeRepository = mockk<AccountAttributeRepository>()
        val accountAnchorRepository = mockk<AccountAnchorRepository>(relaxed = true)
        val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
        val service = AccountService(accountRepository, accountAttributeRepository, accountAnchorRepository, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), eventPublisher)

        then("KVNR changes follow ext_stammdaten without creating or reading a local KVNR anchor") {
            val persons = mockk<PersonDirectory>()
            val account = Account(createdAt = Instant.now()).apply { id = 7L }
            every { accountRepository.findByIdOrNull(7L) } returns account
            every { accountAnchorRepository.findByAttributeTypeAndValue(AttributeType.PERSON_ID, "42") } returns
                AccountAnchor(attributeType = AttributeType.PERSON_ID, value = "42", accountId = 7L, establishedAt = Instant.now())
            every { persons.findPersonIdByKvnr("A123456789") } returns 42L
            service.findAccountByKvnr(" a123456789 ", persons)?.accountId shouldBe 7L

            every { persons.findPersonIdByKvnr("A123456789") } returns null
            every { persons.findPersonIdByKvnr("B987654321") } returns 42L
            service.findAccountByKvnr("A123456789", persons) shouldBe null
            service.findAccountByKvnr("B987654321", persons)?.accountId shouldBe 7L
            verify(exactly = 0) { accountAnchorRepository.findByAttributeTypeAndValue(AttributeType.KVNR, any()) }
            shouldThrow<IllegalStateException> { service.resolveByAnchor(AttributeType.KVNR, "A123456789") }
            shouldThrow<IllegalStateException> { service.anchorValue(7L, AttributeType.KVNR) }
        }

        then("a KVNR claim records provenance only, not a local binding") {
            every { accountAttributeRepository.save(any()) } answers { firstArg() }
            service.recordClaim(7L, Claim(AttributeType.KVNR, "A123456789", ClaimSource.EXT_STAMMDATEN))
            verify(exactly = 1) { accountAttributeRepository.save(match { it.attributeType == AttributeType.KVNR }) }
            verify(exactly = 0) { accountAnchorRepository.save(any()) }
            verify(exactly = 0) { accountRepository.save(any()) }
        }

        then("typed extensions resolve both person ID and email through anchors") {
            val account = Account(createdAt = Instant.now()).apply { id = 7L }
            every { accountRepository.findByIdOrNull(7L) } returns account
            for ((type, value) in listOf(AttributeType.EMAIL to "max@example.com", AttributeType.PERSON_ID to "42")) {
                every { accountAnchorRepository.findByAttributeTypeAndValue(type, value) } returns
                    AccountAnchor(attributeType = type, value = value, accountId = 7L, establishedAt = Instant.now())
            }
            service.findAccountByEmail("  Max@Example.COM ")?.accountId shouldBe 7L
            service.findAccountByPersonId(42L)?.accountId shouldBe 7L
            every { accountAnchorRepository.findByAttributeTypeAndValue(AttributeType.EMAIL, "missing@example.com") } returns null
            every { accountAnchorRepository.findByAttributeTypeAndValue(AttributeType.PERSON_ID, "99") } returns null
            service.findAccountByEmail("missing@example.com") shouldBe null
            service.findAccountByPersonId(99L) shouldBe null
        }

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
