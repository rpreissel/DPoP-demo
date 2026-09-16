package com.example.dpop.account.internal

import com.example.dpop.tool_api.IdentityConflictException
import com.example.dpop.tool_api.MatchedVia
import com.example.dpop.tool_api.PersonDirectory
import com.example.dpop.tool_api.Resolution
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.Claim
import com.example.dpop.tool_spi.ToolId
import com.example.dpop.tool_spi.TrustAnchor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant

/**
 * Pins the resolution policy of the central identity matching (docs/ideen/claims-modell-und-
 * vertrauensanker.md, "Identitaetsauflösung & Matching"): the tool-attested consistency gate,
 * the fixed layer precedence (person_id projection > unique anchor > attribute combination),
 * and the never-guess rule for ambiguous attribute matches. Address fields are no longer part
 * of the consistency check - they are not claims (IdentEidDescriptor.claims).
 */
class IdentityMatchingServiceTest : BehaviorSpec({

    fun service(
        accountRepository: AccountRepository,
        anchorRepository: AccountAnchorRepository,
        attributeRepository: AccountAttributeRepository,
        personDirectory: PersonDirectory
    ) = IdentityMatchingService(accountRepository, anchorRepository, attributeRepository, personDirectory)

    fun account(id: Long): Account = Account(personId = null, createdAt = Instant.now()).apply { this.id = id }

    given("a tool-attested kvnr whose claims contradict the stammdaten on file") {
        val accountRepository = mockk<AccountRepository>()
        val anchorRepository = mockk<AccountAnchorRepository>()
        val attributeRepository = mockk<AccountAttributeRepository>()
        val personDirectory = mockk<PersonDirectory>()
        val resolver = service(accountRepository, anchorRepository, attributeRepository, personDirectory)
        val claims = setOf(
            Claim(AttributeType.KVNR, "A123456789", TrustAnchor.of(ToolId("ident-eid"))),
            Claim(AttributeType.NAME, "Anders", TrustAnchor.of(ToolId("ident-eid"))),
            Claim(AttributeType.VORNAME, "Andrea", TrustAnchor.of(ToolId("ident-eid"))),
            Claim(AttributeType.GEBURTSDATUM, "1970-01-01", TrustAnchor.of(ToolId("ident-eid")))
        )
        every { personDirectory.findPersonIdByKvnr("A123456789") } returns 7L
        every { personDirectory.matchesStammdaten(7L, any()) } returns false

        `when`("resolve is called") {
            then("it refuses to match anything and reports the conflict") {
                shouldThrow<IdentityConflictException> { resolver.resolve(claims) }
                    .message shouldBe "Ausweisdaten stimmen nicht mit den angegebenen Daten ueberein"
            }
        }
    }

    given("a tool-attested kvnr with consistent claims and an existing person account") {
        val accountRepository = mockk<AccountRepository>()
        val anchorRepository = mockk<AccountAnchorRepository>()
        val attributeRepository = mockk<AccountAttributeRepository>()
        val personDirectory = mockk<PersonDirectory>()
        val resolver = service(accountRepository, anchorRepository, attributeRepository, personDirectory)
        val anchor = TrustAnchor.of(ToolId("ident-eid"))
        val claims = setOf(
            Claim(AttributeType.PERSON_ID, "7", anchor),
            Claim(AttributeType.KVNR, "A123456789", anchor),
            Claim(AttributeType.NAME, "Muster", anchor),
            Claim(AttributeType.VORNAME, "Max", anchor),
            Claim(AttributeType.GEBURTSDATUM, "1970-01-01", anchor)
        )
        every { personDirectory.findPersonIdByKvnr("A123456789") } returns 7L
        every { personDirectory.matchesStammdaten(7L, any()) } returns true
        every { accountRepository.findByPersonId(7L) } returns account(7L)

        `when`("resolve is called") {
            then("the consistency gate passes and layer 1 wins via the person projection") {
                resolver.resolve(claims) shouldBe Resolution.ExistingAccount(7L, MatchedVia.PersonId(7L))
            }
        }
    }

    given("stammdaten-attested claims (ident-fsc form)") {
        val accountRepository = mockk<AccountRepository>()
        val anchorRepository = mockk<AccountAnchorRepository>()
        val attributeRepository = mockk<AccountAttributeRepository>()
        val personDirectory = mockk<PersonDirectory>()
        val resolver = service(accountRepository, anchorRepository, attributeRepository, personDirectory)
        val claims = setOf(
            Claim(AttributeType.PERSON_ID, "42", TrustAnchor.EXT_STAMMDATEN),
            Claim(AttributeType.KVNR, "A123456789", TrustAnchor.EXT_STAMMDATEN),
            Claim(AttributeType.NAME, "Muster", TrustAnchor.EXT_STAMMDATEN)
        )
        every { accountRepository.findByPersonId(42L) } returns account(42L)

        `when`("resolve is called") {
            then("no stammdaten interaction happens - the source already vouches for these") {
                resolver.resolve(claims) shouldBe Resolution.ExistingAccount(42L, MatchedVia.PersonId(42L))
                verify(exactly = 0) { personDirectory.findPersonIdByKvnr(any()) }
                verify(exactly = 0) { personDirectory.matchesStammdaten(any(), any()) }
            }
        }
    }

    given("a kvnr anchor already bound to an account, no person claim") {
        val accountRepository = mockk<AccountRepository>()
        val anchorRepository = mockk<AccountAnchorRepository>()
        val attributeRepository = mockk<AccountAttributeRepository>()
        val personDirectory = mockk<PersonDirectory>()
        val resolver = service(accountRepository, anchorRepository, attributeRepository, personDirectory)
        val anchor = TrustAnchor.of(ToolId("ident-eid"))
        val claims = setOf(
            Claim(AttributeType.KVNR, "A123456789", anchor),
            Claim(AttributeType.NAME, "Muster", anchor)
        )
        every { personDirectory.findPersonIdByKvnr("A123456789") } returns null
        every { anchorRepository.findByAnchorTypeAndValue("kvnr", "A123456789") } returns
            AccountAnchor(anchorType = "kvnr", value = "A123456789", accountId = 42L, establishedAt = Instant.now())

        `when`("resolve is called") {
            then("layer 2 wins: the unique anchor lookup") {
                resolver.resolve(claims) shouldBe Resolution.ExistingAccount(42L, MatchedVia.Anchor(AttributeType.KVNR))
            }
        }
    }

    given("attribute matching with a single candidate") {
        val accountRepository = mockk<AccountRepository>()
        val anchorRepository = mockk<AccountAnchorRepository>()
        val attributeRepository = mockk<AccountAttributeRepository>()
        val personDirectory = mockk<PersonDirectory>()
        val resolver = service(accountRepository, anchorRepository, attributeRepository, personDirectory)
        val anchor = TrustAnchor.of(ToolId("ident-eid"))
        val claims = setOf(
            Claim(AttributeType.NAME, "Muster", anchor),
            Claim(AttributeType.VORNAME, "Max", anchor),
            Claim(AttributeType.GEBURTSDATUM, "1970-01-01", anchor)
        )
        every {
            attributeRepository.findAccountIdsMatchingAllThree(
                "name", "muster", "vorname", "max", "geburtsdatum", "1970-01-01", any()
            )
        } returns listOf(7L)

        `when`("resolve is called") {
            then("layer 3 intersects to the one account") {
                resolver.resolve(claims) shouldBe Resolution.ExistingAccount(
                    7L,
                    MatchedVia.Attributes(setOf(AttributeType.NAME, AttributeType.VORNAME, AttributeType.GEBURTSDATUM))
                )
            }
        }
    }

    given("attribute matching with two candidates") {
        val accountRepository = mockk<AccountRepository>()
        val anchorRepository = mockk<AccountAnchorRepository>()
        val attributeRepository = mockk<AccountAttributeRepository>()
        val personDirectory = mockk<PersonDirectory>()
        val resolver = service(accountRepository, anchorRepository, attributeRepository, personDirectory)
        val anchor = TrustAnchor.of(ToolId("ident-eid"))
        val claims = setOf(
            Claim(AttributeType.NAME, "Muster", anchor),
            Claim(AttributeType.VORNAME, "Max", anchor),
            Claim(AttributeType.GEBURTSDATUM, "1970-01-01", anchor)
        )
        every {
            attributeRepository.findAccountIdsMatchingAllThree(
                "name", "muster", "vorname", "max", "geburtsdatum", "1970-01-01", any()
            )
        } returns listOf(7L, 8L)

        `when`("resolve is called") {
            then("it never guesses - the resolution is ambiguous") {
                resolver.resolve(claims) shouldBe Resolution.Ambiguous(listOf(7L, 8L))
            }
        }
    }

    given("attribute matching past the candidate ceiling") {
        val accountRepository = mockk<AccountRepository>()
        val anchorRepository = mockk<AccountAnchorRepository>()
        val attributeRepository = mockk<AccountAttributeRepository>()
        val personDirectory = mockk<PersonDirectory>()
        val resolver = service(accountRepository, anchorRepository, attributeRepository, personDirectory)
        val anchor = TrustAnchor.of(ToolId("ident-eid"))
        val claims = setOf(
            Claim(AttributeType.NAME, "Muster", anchor),
            Claim(AttributeType.VORNAME, "Max", anchor),
            Claim(AttributeType.GEBURTSDATUM, "1970-01-01", anchor)
        )
        val moreThanCeiling = (1L..51L).toList()
        every {
            attributeRepository.findAccountIdsMatchingAllThree(
                "name", "muster", "vorname", "max", "geburtsdatum", "1970-01-01", any()
            )
        } returns moreThanCeiling

        `when`("resolve is called") {
            then("it stays ambiguous rather than widening or guessing") {
                resolver.resolve(claims) shouldBe Resolution.Ambiguous(moreThanCeiling.take(50))
            }
        }
    }

    given("attribute matching with no candidate") {
        val accountRepository = mockk<AccountRepository>()
        val anchorRepository = mockk<AccountAnchorRepository>()
        val attributeRepository = mockk<AccountAttributeRepository>()
        val personDirectory = mockk<PersonDirectory>()
        val resolver = service(accountRepository, anchorRepository, attributeRepository, personDirectory)
        val anchor = TrustAnchor.of(ToolId("ident-eid"))
        val claims = setOf(
            Claim(AttributeType.NAME, "Niemand", anchor),
            Claim(AttributeType.VORNAME, "Niemals", anchor),
            Claim(AttributeType.GEBURTSDATUM, "1970-01-01", anchor)
        )
        every {
            attributeRepository.findAccountIdsMatchingAllThree(
                "name", "niemand", "vorname", "niemals", "geburtsdatum", "1970-01-01", any()
            )
        } returns emptyList()

        `when`("resolve is called") {
            then("nothing matches - a new Interessent") {
                resolver.resolve(claims) shouldBe Resolution.NewInteressent
            }
        }
    }

    given("no claims at all") {
        val accountRepository = mockk<AccountRepository>()
        val anchorRepository = mockk<AccountAnchorRepository>()
        val attributeRepository = mockk<AccountAttributeRepository>()
        val personDirectory = mockk<PersonDirectory>()
        val resolver = service(accountRepository, anchorRepository, attributeRepository, personDirectory)

        `when`("resolve is called") {
            then("the resolution is a new Interessent") {
                resolver.resolve(emptySet()) shouldBe Resolution.NewInteressent
            }
        }
    }
})
