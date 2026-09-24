package com.example.dpop.id_fsc.internal

import com.example.dpop.texts.Text
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
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDate
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

    val birthdate = LocalDate.of(1985, 6, 15)

    fun sessionWithVerifiedPersonalien() = IdFscToolSession(
        toolSessionId = toolSessionId,
        kvnr = "A123456789",
        personId = 7L,
        name = "Muster",
        vorname = "Max",
        geburtsdatum = birthdate
    ).also { data ->
        every { repository.findById(toolSessionId) } returns Optional.of(data)
        every { repository.save(any()) } returns data
    }

    given("verified personal data and a valid code") {
        sessionWithVerifiedPersonalien()
        every { freischaltcodes.pruefe(7L, any()) } returns true

        `when`("the code is submitted") {
            then("it identifies, asserting the master-data attributes as claims under EXT_STAMMDATEN") {
                val outcome = handler.patch(toolSessionId, kvnr = null, name = null, vorname = null, geburtsdatum = null, fsc = "VALIDCODE", personId = null, throttled = false)

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

    given("personal data that does not match the register") {
        `when`("it is submitted") {
            then("it fails right away - no code asked for, none checked - and the data is dropped") {
                clearMocks(freischaltcodes)
                val data = sessionWithVerifiedPersonalien()
                every { personDirectory.matchesPersonalien(7L, "Muster", "Max", birthdate.plusDays(1)) } returns false

                val outcome = handler.patch(toolSessionId, kvnr = null, name = null, vorname = null, geburtsdatum = birthdate.plusDays(1), fsc = null, personId = null, throttled = false)

                outcome shouldBe ToolOutcome.Failed(Text("Die Angaben passen zu keiner versicherten Person"), attemptedPersonId = 7L)
                verify(exactly = 0) { freischaltcodes.pruefe(any(), any()) }
                data.kvnr shouldBe null
                data.geburtsdatum shouldBe null
            }
        }
    }
})
