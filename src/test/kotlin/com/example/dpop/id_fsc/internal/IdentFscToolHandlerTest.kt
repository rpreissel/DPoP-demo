package com.example.dpop.id_fsc.internal

import com.example.dpop.ext_stammdaten.Freischaltcodes
import com.example.dpop.id_fsc.IdentFscDescriptor
import com.example.dpop.tool_api.PersonDirectory
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.Claim
import com.example.dpop.tool_spi.ToolOutcome
import com.example.dpop.tool_spi.ClaimSource
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import java.util.Optional
import java.util.UUID

/**
 * Pins the claims a successful ident-fsc run asserts: FSC is a master-data channel, so every
 * claim carries EXT_STAMMDATEN as its trust anchor, not the tool's own id. No other unit test
 * covers the handler (only [IdentFscFlowTest] covers the pure flow), so this also anchors the
 * `Completed.Identified` wiring itself.
 */
class IdentFscToolHandlerTest : BehaviorSpec({

    val toolSessionId = UUID.randomUUID()
    val repository = mockk<IdFscToolSessionRepository>()
    val freischaltcodes = mockk<Freischaltcodes>()
    val personDirectory = mockk<PersonDirectory>()
    val handler = IdentFscToolHandler(IdentFscDescriptor, repository, freischaltcodes, personDirectory)

    given("a fully filled-in ident-fsc session with a valid code and matching name") {
        val data = IdFscToolSession(
            toolSessionId = toolSessionId,
            kvnr = "A123456789",
            personId = 7L,
            name = "Muster",
            vorname = "Max",
            fscHash = "abc123"
        )
        every { repository.findById(toolSessionId) } returns Optional.of(data)
        every { repository.save(any()) } returns data
        every { personDirectory.matchesName(7L, "Muster", "Max") } returns true
        every { freischaltcodes.pruefe(7L, "abc123") } returns true

        `when`("verification runs") {
            then("it identifies, asserting the master-data attributes as claims under EXT_STAMMDATEN") {
                val outcome = handler.patch(toolSessionId, kvnr = null, name = null, vorname = null, fsc = null, personId = null, throttled = false)

                outcome.shouldBeInstanceOf<ToolOutcome.Completed.Identified>()
                (outcome as ToolOutcome.Completed.Identified).claims shouldBe listOf(
                    Claim(AttributeType.PERSON_ID, "7", ClaimSource.EXT_STAMMDATEN, IdentFscDescriptor.maxAcr),
                    Claim(AttributeType.KVNR, "A123456789", ClaimSource.EXT_STAMMDATEN, IdentFscDescriptor.maxAcr),
                    Claim(AttributeType.NAME, "Muster", ClaimSource.EXT_STAMMDATEN, IdentFscDescriptor.maxAcr),
                    Claim(AttributeType.VORNAME, "Max", ClaimSource.EXT_STAMMDATEN, IdentFscDescriptor.maxAcr)
                )
            }
        }
    }
})