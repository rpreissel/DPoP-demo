package com.example.dpop.orchestrator.kc

import com.example.dpop.account.AccountProfile
import com.example.dpop.ext_stammdaten.PersonData
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

    fun profile(personId: Long?) = AccountProfile(
        accountId = 1L, personId = personId, authenticationMethods = emptyList(),
        email = "who@example.test", emailConfirmedAt = null
    )

    given("an account with a register person bound (PERSON_ID anchor)") {
        val person = PersonData(id = 7L, kvnr = "A123456789", name = "Mustermann", vorname = "Max", geburtsdatum = LocalDate.of(1990, 1, 1))

        then("names and attributes come from the register, claims never override it") {
            val mirror = kcUserMirror(
                profile(personId = 7L), person,
                mapOf(AttributeType.NAME to "Anderer", AttributeType.VORNAME to "Falscher", AttributeType.GEBURTSDATUM to "2000-01-01")
            )

            mirror.firstName shouldBe "Max"
            mirror.lastName shouldBe "Mustermann"
            mirror.attributes shouldBe mapOf("personId" to "7", "kvnr" to "A123456789", "geburtsdatum" to "1990-01-01")
        }
    }

    given("a full-attested Interessent (no register person, but identity claims on the account)") {
        val attested = mapOf(
            AttributeType.NAME to "Musterfrau",
            AttributeType.VORNAME to "Erika",
            AttributeType.GEBURTSDATUM to "1985-05-05"
        )

        then("names and geburtsdatum fall back to the attested claims; personId/kvnr stay absent") {
            val mirror = kcUserMirror(profile(personId = null), null, attested)

            mirror.firstName shouldBe "Erika"
            mirror.lastName shouldBe "Musterfrau"
            mirror.attributes shouldBe mapOf("geburtsdatum" to "1985-05-05")
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

    given("a register person with partial stammdaten (no kvnr on record)") {
        val person = PersonData(id = 9L, kvnr = null, name = "Knapp", vorname = "Karl", geburtsdatum = null)

        then("personId still syncs, and a gap in the register data is filled from the account's attested claims - same identity, gap-filling, not overriding") {
            val mirror = kcUserMirror(
                profile(personId = 9L), person,
                mapOf(AttributeType.GEBURTSDATUM to "1970-01-01")
            )

            mirror.attributes shouldBe mapOf("personId" to "9", "geburtsdatum" to "1970-01-01")
        }
    }
})
