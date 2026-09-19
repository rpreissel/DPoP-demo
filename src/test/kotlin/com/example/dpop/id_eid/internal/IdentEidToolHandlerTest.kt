package com.example.dpop.id_eid.internal

import com.example.dpop.id_eid.IdentEidDescriptor
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.Claim
import com.example.dpop.tool_spi.ToolOutcome
import com.example.dpop.tool_spi.ClaimSource
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

/**
 * Pins the claims a successful ident-eid run asserts: exactly what the card showed, each under
 * the tool's own trust anchor - unlike ident-fsc's EXT_STAMMDATEN (see
 * [com.example.dpop.id_fsc.internal.IdentFscToolHandlerTest]). Deliberately no PERSON_ID and no
 * KVNR: a card carries neither, and binding one is `ident-kvnr`'s act (ADR-18). Address fields
 * stay in the auditDetails blob, not claims.
 */
class IdentEidToolHandlerTest : BehaviorSpec({

    val toolSessionId = UUID.randomUUID()
    val repository = mockk<IdEidToolSessionRepository>()
    val handler = IdentEidToolHandler(IdentEidDescriptor, repository)

    given("an ident-eid session with the card read, waiting for the PIN") {
        val data = IdEidToolSession(
            toolSessionId = toolSessionId,
            name = "Muster",
            vorname = "Max",
            geburtsdatum = LocalDate.of(1970, 1, 1),
            strasse = "Musterweg",
            hausnummer = "1",
            plz = "12345",
            ort = "Musterstadt"
        )
        every { repository.findById(toolSessionId) } returns Optional.of(data)
        every { repository.save(any()) } returns data

        `when`("the correct mock PIN arrives") {
            val outcome = handler.patch(toolSessionId, EidPatchFields(pin = IdentEidFlow.MOCK_PIN))

            then("it attests the card's attributes as claims under its own tool anchor") {
                outcome.shouldBeInstanceOf<ToolOutcome.Completed.Identified>()
                outcome.claims shouldBe listOf(
                    Claim(AttributeType.NAME, "Muster", ClaimSource.of(IdentEidDescriptor.toolId), IdentEidDescriptor.maxAcr),
                    Claim(AttributeType.VORNAME, "Max", ClaimSource.of(IdentEidDescriptor.toolId), IdentEidDescriptor.maxAcr),
                    Claim(AttributeType.GEBURTSDATUM, "1970-01-01", ClaimSource.of(IdentEidDescriptor.toolId), IdentEidDescriptor.maxAcr)
                )
            }

            then("it resolves nobody - no person reference is asserted") {
                outcome.shouldBeInstanceOf<ToolOutcome.Completed.Identified>()
                outcome.personId.shouldBeNull()
            }

            then("the address stays in the audit blob, where no anchor consumer needs it") {
                outcome.shouldBeInstanceOf<ToolOutcome.Completed.Identified>()
                outcome.auditDetails?.get("ort") shouldBe "Musterstadt"
            }
        }

        `when`("a wrong PIN arrives") {
            then("it fails without naming a person - there is none to throttle against") {
                val outcome = handler.patch(toolSessionId, EidPatchFields(pin = "000000"))

                outcome.shouldBeInstanceOf<ToolOutcome.Failed>()
                outcome.attemptedPersonId.shouldBeNull()
            }
        }
    }
})
