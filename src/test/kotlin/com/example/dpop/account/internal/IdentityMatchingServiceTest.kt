package com.example.dpop.account.internal

import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_api.IdentityConflictException
import com.example.dpop.tool_api.MatchedVia
import com.example.dpop.tool_api.PersonDirectory
import com.example.dpop.tool_api.Resolution
import com.example.dpop.tool_spi.Claim
import com.example.dpop.tool_spi.ToolId
import com.example.dpop.tool_spi.ClaimSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant

/**
 * Pins the resolution policy of the central identity matching (docs/ideen/claims-modell-und-
 * vertrauensanker.md, "Identitaetsauflösung & Matching"; docs/ideen/account-attribute-und-trust-
 * vereinheitlichen.md, "Gemeinsame Aufloesung"): the tool-attested consistency gate, the fixed
 * layer precedence (unique anchor - PERSON_ID ranked highest via anchorBindingStrength - then
 * attribute combination), and the never-guess rule for ambiguous attribute matches. No separate
 * PersonId-repository path any more: PERSON_ID resolves through the same `account_anchor` lookup
 * as every other anchor. Address fields are no longer part of the consistency check - they are
 * not claims (IdentEidDescriptor.claims).
 */
class IdentityMatchingServiceTest : BehaviorSpec({

    fun service(
        anchorRepository: AccountAnchorRepository,
        attributeRepository: AccountAttributeRepository,
        personDirectory: PersonDirectory
    ) = IdentityMatchingService(anchorRepository, attributeRepository, personDirectory)

    given("a tool-attested kvnr whose claims contradict the stammdaten on file") {
        val anchorRepository = mockk<AccountAnchorRepository>()
        val attributeRepository = mockk<AccountAttributeRepository>()
        val personDirectory = mockk<PersonDirectory>()
        val resolver = service(anchorRepository, attributeRepository, personDirectory)
        val claims = setOf(
            Claim(AttributeType.KVNR, "A123456789", ClaimSource.of(ToolId("ident-eid"))),
            Claim(AttributeType.NAME, "Anders", ClaimSource.of(ToolId("ident-eid"))),
            Claim(AttributeType.VORNAME, "Andrea", ClaimSource.of(ToolId("ident-eid"))),
            Claim(AttributeType.GEBURTSDATUM, "1970-01-01", ClaimSource.of(ToolId("ident-eid")))
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

    given("a tool-attested kvnr with consistent claims and an existing person_id anchor") {
        val anchorRepository = mockk<AccountAnchorRepository>()
        val attributeRepository = mockk<AccountAttributeRepository>()
        val personDirectory = mockk<PersonDirectory>()
        val resolver = service(anchorRepository, attributeRepository, personDirectory)
        val anchor = ClaimSource.of(ToolId("ident-eid"))
        val claims = setOf(
            Claim(AttributeType.PERSON_ID, "7", anchor),
            Claim(AttributeType.KVNR, "A123456789", anchor),
            Claim(AttributeType.NAME, "Muster", anchor),
            Claim(AttributeType.VORNAME, "Max", anchor),
            Claim(AttributeType.GEBURTSDATUM, "1970-01-01", anchor)
        )
        every { personDirectory.findPersonIdByKvnr("A123456789") } returns 7L
        every { personDirectory.matchesStammdaten(7L, any()) } returns true
        every { anchorRepository.findByAttributeTypeAndValue(AttributeType.PERSON_ID, "7") } returns
            AccountAnchor(attributeType = AttributeType.PERSON_ID, value = "7", accountId = 7L, establishedAt = Instant.now())

        `when`("resolve is called") {
            then("the consistency gate passes and the person_id anchor wins - it ranks above kvnr") {
                resolver.resolve(claims) shouldBe Resolution.ExistingAccount(7L, MatchedVia.Anchor(AttributeType.PERSON_ID))
            }
        }
    }

    given("stammdaten-attested claims (ident-fsc form)") {
        val anchorRepository = mockk<AccountAnchorRepository>()
        val attributeRepository = mockk<AccountAttributeRepository>()
        val personDirectory = mockk<PersonDirectory>()
        val resolver = service(anchorRepository, attributeRepository, personDirectory)
        val claims = setOf(
            Claim(AttributeType.PERSON_ID, "42", ClaimSource.EXT_STAMMDATEN),
            Claim(AttributeType.KVNR, "A123456789", ClaimSource.EXT_STAMMDATEN),
            Claim(AttributeType.NAME, "Muster", ClaimSource.EXT_STAMMDATEN)
        )
        every { anchorRepository.findByAttributeTypeAndValue(AttributeType.PERSON_ID, "42") } returns
            AccountAnchor(attributeType = AttributeType.PERSON_ID, value = "42", accountId = 42L, establishedAt = Instant.now())

        `when`("resolve is called") {
            then("no stammdaten interaction happens - the source already vouches for these") {
                resolver.resolve(claims) shouldBe Resolution.ExistingAccount(42L, MatchedVia.Anchor(AttributeType.PERSON_ID))
                verify(exactly = 0) { personDirectory.findPersonIdByKvnr(any()) }
                verify(exactly = 0) { personDirectory.matchesStammdaten(any(), any()) }
            }
        }
    }

    given("a kvnr anchor already bound to an account, no person claim") {
        val anchorRepository = mockk<AccountAnchorRepository>()
        val attributeRepository = mockk<AccountAttributeRepository>()
        val personDirectory = mockk<PersonDirectory>()
        val resolver = service(anchorRepository, attributeRepository, personDirectory)
        val anchor = ClaimSource.of(ToolId("ident-eid"))
        val claims = setOf(
            Claim(AttributeType.KVNR, "A123456789", anchor),
            Claim(AttributeType.NAME, "Muster", anchor)
        )
        every { personDirectory.findPersonIdByKvnr("A123456789") } returns null
        every { anchorRepository.findByAttributeTypeAndValue(AttributeType.KVNR, "A123456789") } returns
            AccountAnchor(attributeType = AttributeType.KVNR, value = "A123456789", accountId = 42L, establishedAt = Instant.now())

        `when`("resolve is called") {
            then("the unique kvnr anchor lookup wins") {
                resolver.resolve(claims) shouldBe Resolution.ExistingAccount(42L, MatchedVia.Anchor(AttributeType.KVNR))
            }
        }
    }

    given("both a person_id and a kvnr claim, only the kvnr anchored") {
        val anchorRepository = mockk<AccountAnchorRepository>()
        val attributeRepository = mockk<AccountAttributeRepository>()
        val personDirectory = mockk<PersonDirectory>()
        val resolver = service(anchorRepository, attributeRepository, personDirectory)
        val anchor = ClaimSource.EXT_STAMMDATEN
        val claims = setOf(
            Claim(AttributeType.PERSON_ID, "7", anchor),
            Claim(AttributeType.KVNR, "A123456789", anchor)
        )
        every { anchorRepository.findByAttributeTypeAndValue(AttributeType.PERSON_ID, "7") } returns null
        every { anchorRepository.findByAttributeTypeAndValue(AttributeType.KVNR, "A123456789") } returns
            AccountAnchor(attributeType = AttributeType.KVNR, value = "A123456789", accountId = 42L, establishedAt = Instant.now())

        `when`("resolve is called") {
            then("person_id is tried first (higher binding strength), falls through to kvnr") {
                resolver.resolve(claims) shouldBe Resolution.ExistingAccount(42L, MatchedVia.Anchor(AttributeType.KVNR))
            }
        }
    }

    given("attribute matching with a single candidate") {
        val anchorRepository = mockk<AccountAnchorRepository>()
        val attributeRepository = mockk<AccountAttributeRepository>()
        val personDirectory = mockk<PersonDirectory>()
        val resolver = service(anchorRepository, attributeRepository, personDirectory)
        val anchor = ClaimSource.of(ToolId("ident-eid"))
        val claims = setOf(
            Claim(AttributeType.NAME, "Muster", anchor),
            Claim(AttributeType.VORNAME, "Max", anchor),
            Claim(AttributeType.GEBURTSDATUM, "1970-01-01", anchor)
        )
        every {
            attributeRepository.findAccountIdsMatchingAllThree(
                AttributeType.NAME, "muster", AttributeType.VORNAME, "max", AttributeType.GEBURTSDATUM, "1970-01-01", any()
            )
        } returns listOf(7L)

        `when`("resolve is called") {
            then("layer 2 intersects to the one account") {
                resolver.resolve(claims) shouldBe Resolution.ExistingAccount(
                    7L,
                    MatchedVia.Attributes(setOf(AttributeType.NAME, AttributeType.VORNAME, AttributeType.GEBURTSDATUM))
                )
            }
        }
    }

    given("attribute matching with two candidates") {
        val anchorRepository = mockk<AccountAnchorRepository>()
        val attributeRepository = mockk<AccountAttributeRepository>()
        val personDirectory = mockk<PersonDirectory>()
        val resolver = service(anchorRepository, attributeRepository, personDirectory)
        val anchor = ClaimSource.of(ToolId("ident-eid"))
        val claims = setOf(
            Claim(AttributeType.NAME, "Muster", anchor),
            Claim(AttributeType.VORNAME, "Max", anchor),
            Claim(AttributeType.GEBURTSDATUM, "1970-01-01", anchor)
        )
        every {
            attributeRepository.findAccountIdsMatchingAllThree(
                AttributeType.NAME, "muster", AttributeType.VORNAME, "max", AttributeType.GEBURTSDATUM, "1970-01-01", any()
            )
        } returns listOf(7L, 8L)

        `when`("resolve is called") {
            then("it never guesses - the resolution is ambiguous") {
                resolver.resolve(claims) shouldBe Resolution.Ambiguous(2)
            }
        }
    }

    given("attribute matching past the candidate ceiling") {
        val anchorRepository = mockk<AccountAnchorRepository>()
        val attributeRepository = mockk<AccountAttributeRepository>()
        val personDirectory = mockk<PersonDirectory>()
        val resolver = service(anchorRepository, attributeRepository, personDirectory)
        val anchor = ClaimSource.of(ToolId("ident-eid"))
        val claims = setOf(
            Claim(AttributeType.NAME, "Muster", anchor),
            Claim(AttributeType.VORNAME, "Max", anchor),
            Claim(AttributeType.GEBURTSDATUM, "1970-01-01", anchor)
        )
        val moreThanCeiling = (1L..51L).toList()
        every {
            attributeRepository.findAccountIdsMatchingAllThree(
                AttributeType.NAME, "muster", AttributeType.VORNAME, "max", AttributeType.GEBURTSDATUM, "1970-01-01", any()
            )
        } returns moreThanCeiling

        `when`("resolve is called") {
            then("it stays ambiguous rather than widening or guessing") {
                resolver.resolve(claims) shouldBe Resolution.Ambiguous(moreThanCeiling.size)
            }
        }
    }

    given("attribute matching with no candidate") {
        val anchorRepository = mockk<AccountAnchorRepository>()
        val attributeRepository = mockk<AccountAttributeRepository>()
        val personDirectory = mockk<PersonDirectory>()
        val resolver = service(anchorRepository, attributeRepository, personDirectory)
        val anchor = ClaimSource.of(ToolId("ident-eid"))
        val claims = setOf(
            Claim(AttributeType.NAME, "Niemand", anchor),
            Claim(AttributeType.VORNAME, "Niemals", anchor),
            Claim(AttributeType.GEBURTSDATUM, "1970-01-01", anchor)
        )
        every {
            attributeRepository.findAccountIdsMatchingAllThree(
                AttributeType.NAME, "niemand", AttributeType.VORNAME, "niemals", AttributeType.GEBURTSDATUM, "1970-01-01", any()
            )
        } returns emptyList()

        `when`("resolve is called") {
            then("nothing matches - a new Interessent") {
                resolver.resolve(claims) shouldBe Resolution.NewInteressent
            }
        }
    }

    given("no claims at all") {
        val anchorRepository = mockk<AccountAnchorRepository>()
        val attributeRepository = mockk<AccountAttributeRepository>()
        val personDirectory = mockk<PersonDirectory>()
        val resolver = service(anchorRepository, attributeRepository, personDirectory)

        `when`("resolve is called") {
            then("the resolution is a new Interessent") {
                resolver.resolve(emptySet()) shouldBe Resolution.NewInteressent
            }
        }
    }
})
