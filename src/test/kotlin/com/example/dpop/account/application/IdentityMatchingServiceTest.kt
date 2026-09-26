package com.example.dpop.account.application

import com.example.dpop.account.application.IdentityMatchingService
import com.example.dpop.account.infrastructure.AccountAnchor
import com.example.dpop.account.infrastructure.AccountAnchorRepository
import com.example.dpop.account.infrastructure.AccountClaim
import com.example.dpop.account.infrastructure.AccountClaimRepository
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_api.ClaimedIdentity
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
import java.time.LocalDate

/**
 * Pins the resolution policy of the central identity matching (docs/archiv/claims-modell-und-
 * vertrauensanker.md, "Identitaetsauflösung & Matching"; docs/ideen/account-attribute-und-trust-
 * vereinheitlichen.md, "Gemeinsame Aufloesung"): the tool-attested consistency gate and
 * anchor-only resolution (ADR-19) - unique anchor lookups through `account.anchor`, PERSON_ID
 * ranked highest via anchorBindingStrength, the eID card pseudonym as the recognition anchor for
 * eid attestations, and no attribute matching against the account stock at all anymore. No
 * separate PersonId-repository path: PERSON_ID resolves through the same `account.anchor` lookup
 * as every other anchor. The consistency gate compares only name/vorname/geburtsdatum - address
 * fields and restricted_id are claims the register cannot confirm (IdentEidDescriptor.claims).
 */
class IdentityMatchingServiceTest : BehaviorSpec({

    fun service(
        anchorRepository: AccountAnchorRepository,
        claimRepository: AccountClaimRepository,
        personDirectory: PersonDirectory
    ) = IdentityMatchingService(anchorRepository, claimRepository, personDirectory)

    given("a tool-attested kvnr with consistent claims and an existing person_id anchor") {
        val anchorRepository = mockk<AccountAnchorRepository>()
        val claimRepository = mockk<AccountClaimRepository>()
        val personDirectory = mockk<PersonDirectory>()
        val resolver = service(anchorRepository, claimRepository, personDirectory)
        val anchor = ClaimSource.of(ToolId("ident-eid"))
        val claims = setOf(
            Claim(AttributeType.PERSON_ID, "P000000007", anchor),
            Claim(AttributeType.KVNR, "A123456789", anchor),
            Claim(AttributeType.FAMILY_NAME, "Muster", anchor),
            Claim(AttributeType.GIVEN_NAMES, "Max", anchor),
            Claim(AttributeType.BIRTH_DATE, "1970-01-01", anchor)
        )
        every { personDirectory.findPersonIdByKvnr("A123456789") } returns "P000000007"
        every { personDirectory.matchesMasterData("P000000007", any()) } returns true
        every { anchorRepository.findByAttributeTypeAndValue(AttributeType.PERSON_ID, "P000000007") } returns
            AccountAnchor(attributeType = AttributeType.PERSON_ID, value = "P000000007", accountId = 7L, establishedAt = Instant.now())

        `when`("resolve is called") {
            then("the consistency gate passes and the person_id anchor wins - it ranks above kvnr") {
                resolver.resolve(claims) shouldBe Resolution.ExistingAccount(7L, MatchedVia.Anchor(AttributeType.PERSON_ID))
            }
        }
    }

    given("stammdaten-attested claims (ident-fsc form)") {
        val anchorRepository = mockk<AccountAnchorRepository>()
        val claimRepository = mockk<AccountClaimRepository>()
        val personDirectory = mockk<PersonDirectory>()
        val resolver = service(anchorRepository, claimRepository, personDirectory)
        val claims = setOf(
            Claim(AttributeType.PERSON_ID, "P000000042", ClaimSource.PERSON_DIRECTORY),
            Claim(AttributeType.KVNR, "A123456789", ClaimSource.PERSON_DIRECTORY),
            Claim(AttributeType.FAMILY_NAME, "Muster", ClaimSource.PERSON_DIRECTORY)
        )
        every { anchorRepository.findByAttributeTypeAndValue(AttributeType.PERSON_ID, "P000000042") } returns
            AccountAnchor(attributeType = AttributeType.PERSON_ID, value = "P000000042", accountId = 42L, establishedAt = Instant.now())

        `when`("resolve is called") {
            then("no stammdaten interaction happens - the source already vouches for these") {
                resolver.resolve(claims) shouldBe Resolution.ExistingAccount(42L, MatchedVia.Anchor(AttributeType.PERSON_ID))
                verify(exactly = 0) { personDirectory.findPersonIdByKvnr(any()) }
                verify(exactly = 0) { personDirectory.matchesMasterData(any(), any()) }
            }
        }
    }

    given("a live kvnr-to-person mapping, no person claim") {
        val anchorRepository = mockk<AccountAnchorRepository>()
        val claimRepository = mockk<AccountClaimRepository>()
        val personDirectory = mockk<PersonDirectory>()
        val resolver = service(anchorRepository, claimRepository, personDirectory)
        val anchor = ClaimSource.of(ToolId("ident-eid"))
        val claims = setOf(
            Claim(AttributeType.KVNR, "A123456789", anchor),
            Claim(AttributeType.FAMILY_NAME, "Muster", anchor)
        )
        every { personDirectory.findPersonIdByKvnr("A123456789") } returns "P000000007"
        every { personDirectory.matchesMasterData("P000000007", any()) } returns true
        every { anchorRepository.findByAttributeTypeAndValue(AttributeType.PERSON_ID, "P000000007") } returns
            AccountAnchor(attributeType = AttributeType.PERSON_ID, value = "P000000007", accountId = 42L, establishedAt = Instant.now())

        `when`("resolve is called") {
            then("the current external mapping resolves through the person_id anchor") {
                resolver.resolve(claims) shouldBe Resolution.ExistingAccount(42L, MatchedVia.Anchor(AttributeType.PERSON_ID))
                verify(exactly = 0) { anchorRepository.findByAttributeTypeAndValue(AttributeType.KVNR, any()) }
            }
        }
    }

    given("both a person_id and a kvnr claim, with only a stale local kvnr anchor") {
        val anchorRepository = mockk<AccountAnchorRepository>()
        val claimRepository = mockk<AccountClaimRepository>()
        val personDirectory = mockk<PersonDirectory>()
        val resolver = service(anchorRepository, claimRepository, personDirectory)
        val anchor = ClaimSource.PERSON_DIRECTORY
        val claims = setOf(
            Claim(AttributeType.PERSON_ID, "P000000007", anchor),
            Claim(AttributeType.KVNR, "A123456789", anchor)
        )
        every { anchorRepository.findByAttributeTypeAndValue(AttributeType.PERSON_ID, "P000000007") } returns null
        every { anchorRepository.findByAttributeTypeAndValue(AttributeType.KVNR, "A123456789") } returns
            AccountAnchor(attributeType = AttributeType.KVNR, value = "A123456789", accountId = 42L, establishedAt = Instant.now())

        `when`("resolve is called") {
            then("it does not adopt an account through the stale local kvnr anchor") {
                resolver.resolve(claims) shouldBe Resolution.Unresolved
                verify(exactly = 0) { anchorRepository.findByAttributeTypeAndValue(AttributeType.KVNR, any()) }
            }
        }
    }

    given("person_id and email anchors pointing to different accounts") {
        val anchorRepository = mockk<AccountAnchorRepository>()
        val resolver = service(anchorRepository, mockk(), mockk())
        every { anchorRepository.findByAttributeTypeAndValue(AttributeType.PERSON_ID, "P000000007") } returns
            AccountAnchor(attributeType = AttributeType.PERSON_ID, value = "P000000007", accountId = 7L, establishedAt = Instant.now())
        every { anchorRepository.findByAttributeTypeAndValue(AttributeType.EMAIL, "other@example.com") } returns
            AccountAnchor(attributeType = AttributeType.EMAIL, value = "other@example.com", accountId = 8L, establishedAt = Instant.now())
        then("claim order cannot hide a conflicting owner") {
            val claims = listOf(
                Claim(AttributeType.PERSON_ID, "P000000007", ClaimSource.PERSON_DIRECTORY),
                Claim(AttributeType.EMAIL, "other@example.com", ClaimSource.PERSON_DIRECTORY)
            )
            for (ordered in listOf(claims, claims.reversed())) {
                shouldThrow<IdentityConflictException> { resolver.resolve(ordered.toSet()) }
            }
        }
    }

    given("an eid attestation whose restricted_id anchor an account already holds") {
        val anchorRepository = mockk<AccountAnchorRepository>()
        val claimRepository = mockk<AccountClaimRepository>()
        val personDirectory = mockk<PersonDirectory>()
        val resolver = service(anchorRepository, claimRepository, personDirectory)
        val anchor = ClaimSource.of(ToolId("ident-eid"))
        val claims = setOf(
            Claim(AttributeType.FAMILY_NAME, "Muster", anchor),
            Claim(AttributeType.GIVEN_NAMES, "Max", anchor),
            Claim(AttributeType.BIRTH_DATE, "1970-01-01", anchor),
            Claim(AttributeType.EID_RESTRICTED_ID, "T0103005K1D5S0V8T9W6UM2RTX", anchor)
        )
        every {
            anchorRepository.findByAttributeTypeAndValue(AttributeType.EID_RESTRICTED_ID, "T0103005K1D5S0V8T9W6UM2RTX")
        } returns AccountAnchor(
            attributeType = AttributeType.EID_RESTRICTED_ID, value = "T0103005K1D5S0V8T9W6UM2RTX", accountId = 7L, establishedAt = Instant.now()
        )

        `when`("resolve is called") {
            then("the card pseudonym recognizes the account the earlier eid run created - ADR-19") {
                resolver.resolve(claims) shouldBe Resolution.ExistingAccount(
                    7L,
                    MatchedVia.Anchor(AttributeType.EID_RESTRICTED_ID)
                )
                // No attribute matching, no stammdaten round trip - the anchor alone decides.
                verify(exactly = 0) { personDirectory.findPersonIdByKvnr(any()) }
                verify(exactly = 0) { personDirectory.matchesMasterData(any(), any()) }
            }
        }
    }

    given("an eid attestation whose restricted_id no account holds yet") {
        val anchorRepository = mockk<AccountAnchorRepository>()
        val claimRepository = mockk<AccountClaimRepository>()
        val personDirectory = mockk<PersonDirectory>()
        val resolver = service(anchorRepository, claimRepository, personDirectory)
        val anchor = ClaimSource.of(ToolId("ident-eid"))
        val claims = setOf(
            Claim(AttributeType.FAMILY_NAME, "Niemand", anchor),
            Claim(AttributeType.GIVEN_NAMES, "Niemals", anchor),
            Claim(AttributeType.BIRTH_DATE, "1970-01-01", anchor),
            Claim(AttributeType.EID_RESTRICTED_ID, "T0909090Z9X8Y7W6V5U4T3S2R1", anchor)
        )
        every {
            anchorRepository.findByAttributeTypeAndValue(AttributeType.EID_RESTRICTED_ID, "T0909090Z9X8Y7W6V5U4T3S2R1")
        } returns null

        `when`("resolve is called") {
            then("nothing matches - a new Interessent, the anchor gets established by the adopting side") {
                resolver.resolve(claims) shouldBe Resolution.Unresolved
            }
        }
    }

    given("name/vorname/geburtsdatum alone - attributes, no anchor (ADR-19)") {
        val anchorRepository = mockk<AccountAnchorRepository>()
        val claimRepository = mockk<AccountClaimRepository>()
        val personDirectory = mockk<PersonDirectory>()
        val resolver = service(anchorRepository, claimRepository, personDirectory)
        val anchor = ClaimSource.of(ToolId("ident-eid"))
        val claims = setOf(
            Claim(AttributeType.FAMILY_NAME, "Muster", anchor),
            Claim(AttributeType.GIVEN_NAMES, "Max", anchor),
            Claim(AttributeType.BIRTH_DATE, "1970-01-01", anchor)
        )

        `when`("resolve is called") {
            then("no account is matched by attribute combination - attributes never resolve") {
                resolver.resolve(claims) shouldBe Resolution.Unresolved
                verify(exactly = 0) { anchorRepository.findByAttributeTypeAndValue(any(), any()) }
            }
        }
    }

    given("no claims at all") {
        val anchorRepository = mockk<AccountAnchorRepository>()
        val claimRepository = mockk<AccountClaimRepository>()
        val personDirectory = mockk<PersonDirectory>()
        val resolver = service(anchorRepository, claimRepository, personDirectory)

        `when`("resolve is called") {
            then("the resolution is a new Interessent") {
                resolver.resolve(emptySet()) shouldBe Resolution.Unresolved
            }
        }
    }

    given("attestedIdentityMatches - the guard a correlation step leans on (ADR-18)") {
        fun claim(type: AttributeType, value: String, source: ClaimSource) = AccountClaim(
            accountId = 1L, attributeType = type, value = value, claimSource = source.value, establishedAt = Instant.now()
        )
        val attested = listOf(
            claim(AttributeType.FAMILY_NAME, "Muster", ClaimSource.of(ToolId("ident-eid"))),
            claim(AttributeType.GIVEN_NAMES, "Max", ClaimSource.of(ToolId("ident-eid"))),
            claim(AttributeType.BIRTH_DATE, "1985-06-15", ClaimSource.of(ToolId("ident-eid")))
        )

        `when`("the register's person matches what the account attested") {
            val claimRepository = mockk<AccountClaimRepository>()
            val personDirectory = mockk<PersonDirectory>()
            val resolver = service(mockk(), claimRepository, personDirectory)
            every { claimRepository.findEstablished(1L) } returns attested
            every { personDirectory.hasNamesake("P000000042") } returns false
            every { personDirectory.matchesMasterData("P000000042", any()) } returns true

            then("it passes, carrying exactly the attested attributes into the comparison") {
                resolver.attestedIdentityMatches(1L, "P000000042") shouldBe true
                verify {
                    personDirectory.matchesMasterData(
                        "P000000042",
                        ClaimedIdentity(familyName = "Muster", givenNames = "Max", birthDate = LocalDate.of(1985, 6, 15))
                    )
                }
            }
        }

        `when`("the register's person contradicts the attested identity - somebody else's number") {
            val claimRepository = mockk<AccountClaimRepository>()
            val personDirectory = mockk<PersonDirectory>()
            val resolver = service(mockk(), claimRepository, personDirectory)
            every { claimRepository.findEstablished(1L) } returns attested
            every { personDirectory.hasNamesake("P000000099") } returns false
            every { personDirectory.matchesMasterData("P000000099", any()) } returns false

            then("it refuses") {
                resolver.attestedIdentityMatches(1L, "P000000099") shouldBe false
            }
        }

        val withAddress = attested + listOf(
            claim(AttributeType.STREET_ADDRESS, "Musterstraße 1", ClaimSource.of(ToolId("ident-eid"))),
            claim(AttributeType.POSTAL_CODE, "12345", ClaimSource.of(ToolId("ident-eid"))),
            claim(AttributeType.LOCALITY, "Musterstadt", ClaimSource.of(ToolId("ident-eid")))
        )

        `when`("the register holds a namesake born the same day (ADR-18, addendum 2026-09-26)") {
            then("name and date of birth are not enough - without an attested address it refuses without asking") {
                val claimRepository = mockk<AccountClaimRepository>()
                val personDirectory = mockk<PersonDirectory>()
                every { claimRepository.findEstablished(1L) } returns attested
                every { personDirectory.hasNamesake("P000000042") } returns true

                service(mockk(), claimRepository, personDirectory).attestedIdentityMatches(1L, "P000000042") shouldBe false
                verify(exactly = 0) { personDirectory.matchesMasterData(any(), any()) }
            }

            then("with an attested address, the address is compared too") {
                val claimRepository = mockk<AccountClaimRepository>()
                val personDirectory = mockk<PersonDirectory>()
                every { claimRepository.findEstablished(1L) } returns withAddress
                every { personDirectory.hasNamesake("P000000042") } returns true
                every { personDirectory.matchesMasterData("P000000042", any()) } returns true

                service(mockk(), claimRepository, personDirectory).attestedIdentityMatches(1L, "P000000042") shouldBe true
                verify {
                    personDirectory.matchesMasterData(
                        "P000000042",
                        ClaimedIdentity(
                            familyName = "Muster", givenNames = "Max", birthDate = LocalDate.of(1985, 6, 15),
                            streetAddress = "Musterstraße 1", postalCode = "12345", locality = "Musterstadt"
                        )
                    )
                }
            }
        }

        `when`("the attestation lacks the date of birth") {
            then("it refuses - a missing attribute is never skipped into a name-only match") {
                val claimRepository = mockk<AccountClaimRepository>()
                val personDirectory = mockk<PersonDirectory>()
                every { claimRepository.findEstablished(1L) } returns attested.filterNot { it.attributeType == AttributeType.BIRTH_DATE }

                service(mockk(), claimRepository, personDirectory).attestedIdentityMatches(1L, "P000000042") shouldBe false
                verify(exactly = 0) { personDirectory.matchesMasterData(any(), any()) }
            }
        }

        `when`("the account attested nothing at all") {
            val claimRepository = mockk<AccountClaimRepository>()
            val personDirectory = mockk<PersonDirectory>()
            val resolver = service(mockk(), claimRepository, personDirectory)
            every { claimRepository.findEstablished(1L) } returns emptyList()

            then("it refuses without even asking - an empty ClaimedIdentity would match vacuously") {
                resolver.attestedIdentityMatches(1L, "P000000042") shouldBe false
                verify(exactly = 0) { personDirectory.matchesMasterData(any(), any()) }
            }
        }
    }

    given("attestationFits - an Interessent may not take a second identity (review 2026-09, Phase F)") {
        val eid = ClaimSource.of(ToolId("ident-eid"))
        fun claim(type: AttributeType, value: String) = AccountClaim(
            accountId = 1L, attributeType = type, value = value, claimSource = eid.value, establishedAt = Instant.now()
        )
        val claimRepository = mockk<AccountClaimRepository>()
        val resolver = service(mockk(), claimRepository, mockk())

        then("an account that attested nothing yet takes any identity") {
            every { claimRepository.findEstablished(1L) } returns emptyList()
            resolver.attestationFits(1L, setOf(Claim(AttributeType.FAMILY_NAME, "Anders", eid))) shouldBe true
        }

        then("the same person in another spelling fits - case, umlauts and diacritics do not count") {
            every { claimRepository.findEstablished(1L) } returns listOf(
                claim(AttributeType.FAMILY_NAME, "Müller"), claim(AttributeType.GIVEN_NAMES, "José"), claim(AttributeType.BIRTH_DATE, "1985-06-15")
            )
            resolver.attestationFits(1L, setOf(
                Claim(AttributeType.FAMILY_NAME, "MUELLER", eid), Claim(AttributeType.GIVEN_NAMES, "Jose", eid), Claim(AttributeType.BIRTH_DATE, "1985-06-15", eid)
            )) shouldBe true
        }

        then("somebody else does not - a different birthdate or name is a second identity") {
            every { claimRepository.findEstablished(1L) } returns listOf(
                claim(AttributeType.FAMILY_NAME, "Müller"), claim(AttributeType.BIRTH_DATE, "1985-06-15")
            )
            resolver.attestationFits(1L, setOf(Claim(AttributeType.BIRTH_DATE, "1990-01-01", eid))) shouldBe false
            resolver.attestationFits(1L, setOf(Claim(AttributeType.FAMILY_NAME, "Schmidt", eid))) shouldBe false
        }
    }
})
