package com.example.dpop.id_eid.internal

import com.example.dpop.id_eid.IdentEidDescriptor
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.Claim
import com.example.dpop.tool_spi.ToolOutcome
import com.example.dpop.tool_spi.ClaimSource
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

/**
 * Pins the claims a successful ident-eid run asserts: an eID run is the proving tool itself, so
 * every claim carries the tool's own id as its trust anchor - unlike ident-fsc's EXT_STAMMDATEN
 * (see [com.example.dpop.id_fsc.internal.IdentFscToolHandlerTest]). Address fields stay in the
 * auditDetails blob, not claims. No other unit test covers the handler (only
 * [IdentEidFlowTest] covers the pure flow), so this also anchors the `Completed.Identified`
 * wiring itself.
 */
class IdentEidToolHandlerTest : BehaviorSpec({

    val toolSessionId = UUID.randomUUID()
    val repository = mockk<IdEidToolSessionRepository>()
    val handler = IdentEidToolHandler(IdentEidDescriptor, repository)

    given("an ident-eid session with lookup and card data in, waiting for the PIN") {
        val data = IdEidToolSession(
            toolSessionId = toolSessionId,
            kvnr = "A123456789",
            personId = 7L,
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
            then("it identifies, asserting the card's attributes as claims under its own tool anchor") {
                val outcome = handler.patch(toolSessionId, EidPatchFields(pin = IdentEidFlow.MOCK_PIN), personId = null, throttled = false)

                outcome.shouldBeInstanceOf<ToolOutcome.Completed.Identified>()
                (outcome as ToolOutcome.Completed.Identified).claims shouldBe listOf(
                    Claim(AttributeType.PERSON_ID, "7", ClaimSource.of(IdentEidDescriptor.toolId), IdentEidDescriptor.maxAcr),
                    Claim(AttributeType.KVNR, "A123456789", ClaimSource.of(IdentEidDescriptor.toolId), IdentEidDescriptor.maxAcr),
                    Claim(AttributeType.NAME, "Muster", ClaimSource.of(IdentEidDescriptor.toolId), IdentEidDescriptor.maxAcr),
                    Claim(AttributeType.VORNAME, "Max", ClaimSource.of(IdentEidDescriptor.toolId), IdentEidDescriptor.maxAcr),
                    Claim(AttributeType.GEBURTSDATUM, "1970-01-01", ClaimSource.of(IdentEidDescriptor.toolId), IdentEidDescriptor.maxAcr)
                )
            }
        }
    }
})