package com.example.dpop.orchestrator.kc

import com.example.dpop.account.AccountProfile
import com.example.dpop.tool_api.PersonRecord
import com.example.dpop.tool_spi.AttributeType
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/**
 * Pure unit test of the account -> Keycloak-user mapping shared by [KeycloakAccountSyncListener]'s
 * event path and [KeycloakAccountSyncService]'s full reconciliation - the precedence is the point:
 * register person first (authoritative stammdaten), then the account's own attested claims
 * (full-attested Interessent, ADR-18), placeholders only for an account that has neither.
 */
class KeycloakAccountMirrorTest : BehaviorSpec({

    fun profile(personId: String?) = AccountProfile(
        accountId = 1L, personId = personId, authenticationMethods = emptyList(),
        email = "who@example.test", emailConfirmedAt = null
    )

    given("an account with a register person bound (PERSON_ID anchor)") {
        // The port hands out one street line; joining "Musterweg" and "1" is the directory's job.
        val person = PersonRecord(
            kvnr = "A123456789", familyName = "Mustermann", givenNames = "Max", birthDate = LocalDate.of(1990, 1, 1),
            streetAddress = "Musterweg 1", postalCode = "12345", locality = "Musterstadt", insuranceNumber = "10000001"
        )

        then("names, attributes and address come from the register, claims never override it") {
            val mirror = kcUserMirror(
                profile(personId = "P000000007"), person,
                mapOf(
                    AttributeType.FAMILY_NAME to "Anderer", AttributeType.GIVEN_NAMES to "Falscher", AttributeType.BIRTH_DATE to "2000-01-01",
                    AttributeType.STREET_ADDRESS to "Andere Gasse 9", AttributeType.POSTAL_CODE to "99999", AttributeType.LOCALITY to "Nirgendwo"
                )
            )

            mirror.firstName shouldBe "Max"
            mirror.lastName shouldBe "Mustermann"
            mirror.attributes shouldBe mapOf(
                "personId" to "P000000007", "kvnr" to "A123456789", "versnr" to "10000001", "birthDate" to "1990-01-01",
                "streetAddress" to "Musterweg 1", "postalCode" to "12345", "locality" to "Musterstadt"
            )
        }
    }

    given("a full-attested Interessent (no register person, but identity claims on the account)") {
        val attested = mapOf(
            AttributeType.FAMILY_NAME to "Musterfrau",
            AttributeType.GIVEN_NAMES to "Erika",
            AttributeType.BIRTH_DATE to "1985-05-05",
            AttributeType.STREET_ADDRESS to "Musterweg 1",
            AttributeType.POSTAL_CODE to "12345",
            AttributeType.LOCALITY to "Musterstadt"
        )

        then("names, geburtsdatum and address fall back to the attested claims; personId/kvnr stay absent") {
            val mirror = kcUserMirror(profile(personId = null), null, attested)

            mirror.firstName shouldBe "Erika"
            mirror.lastName shouldBe "Musterfrau"
            mirror.attributes shouldBe mapOf(
                "birthDate" to "1985-05-05",
                "streetAddress" to "Musterweg 1", "postalCode" to "12345", "locality" to "Musterstadt"
            )
        }
    }

    given("an account with neither a register person nor attested claims (enrollment first)") {
        then("the placeholders stand in, and no attribute is written") {
            val mirror = kcUserMirror(profile(personId = null), null, emptyMap())

            mirror.firstName shouldBe UNIDENTIFIED_FIRST_NAME
            mirror.lastName shouldBe UNIDENTIFIED_LAST_NAME
            mirror.attributes shouldBe emptyMap()
        }
    }

    given("a register person with partial stammdaten (no kvnr, no address on record)") {
        val person = PersonRecord(kvnr = null, familyName = "Knapp", givenNames = "Karl", birthDate = null, streetAddress = null, postalCode = null, locality = null, insuranceNumber = null)

        then("a gap in the Personenverzeichnis stays a gap - an old attested claim never resurfaces for a bound account (ADR-34)") {
            val mirror = kcUserMirror(
                profile(personId = "P000000009"), person,
                mapOf(
                    AttributeType.BIRTH_DATE to "1970-01-01",
                    AttributeType.STREET_ADDRESS to "Lückenweg 2", AttributeType.POSTAL_CODE to "54321", AttributeType.LOCALITY to "Lückendorf"
                )
            )

            mirror.attributes shouldBe mapOf("personId" to "P000000009")
        }
    }
})
