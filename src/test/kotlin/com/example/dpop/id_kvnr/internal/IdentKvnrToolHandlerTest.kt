package com.example.dpop.id_kvnr.internal

import com.example.dpop.tool_api.PersonDirectory
import com.example.dpop.id_kvnr.IdentKvnrDescriptor
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.Claim
import com.example.dpop.tool_spi.ClaimSource
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.ToolCategory
import com.example.dpop.tool_spi.ToolOutcome
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import java.util.Optional
import java.util.UUID

/**
 * Pins what the correlation step asserts (docs/12-entscheidungen.md ADR-18): the register's own
 * person reference and KVNR, both under `PERSON_DIRECTORY` - unlike `ident-eid`, which vouches for
 * the card's attributes itself. The identity match that makes this safe lives in the account
 * module ([com.example.dpop.account.internal.IdentityMatchingServiceTest]), not here: this tool
 * never sees accounts.
 */
class IdentKvnrToolHandlerTest : BehaviorSpec({

    val toolSessionId = UUID.randomUUID()
    val repository = mockk<IdKvnrToolSessionRepository>()
    val personDirectory = mockk<PersonDirectory>()
    val handler = IdentKvnrToolHandler(IdentKvnrDescriptor, repository, personDirectory)
    val data = IdKvnrToolSession(toolSessionId = toolSessionId)

    beforeTest {
        every { repository.findById(toolSessionId) } returns Optional.of(data)
        every { repository.save(any()) } returns data
        every { personDirectory.versnrOf(any()) } returns null
    }

    given("a KVNR the register resolves") {
        then("it asserts the person reference and the number, both vouched for by the register") {
            val outcome = handler.patch(toolSessionId, "A123456789", partnernr = null, personId = "P000000042")

            outcome.shouldBeInstanceOf<ToolOutcome.Completed.Identified>()
            outcome.claims shouldBe listOf(
                Claim(AttributeType.PERSON_ID, "P000000042", ClaimSource.PERSON_DIRECTORY, IdentKvnrDescriptor.maxAcr),
                Claim(AttributeType.KVNR, "A123456789", ClaimSource.PERSON_DIRECTORY, IdentKvnrDescriptor.maxAcr)
            )
        }
    }

    given("a person insured with us") {
        then("the Versicherungsnummer comes along as an anchor claim (ADR-34)") {
            every { personDirectory.versnrOf("P000000042") } returns "10000001"

            val outcome = handler.patch(toolSessionId, "A123456789", partnernr = null, personId = "P000000042")

            outcome.shouldBeInstanceOf<ToolOutcome.Completed.Identified>()
            outcome.claims.last() shouldBe Claim(AttributeType.VERSNR, "10000001", ClaimSource.PERSON_DIRECTORY, IdentKvnrDescriptor.maxAcr)
        }
    }

    given("a Partner without a KVNR, identified by Partnernummer (ADR-34)") {
        then("it asserts the person reference only - no KVNR claim") {
            val outcome = handler.patch(toolSessionId, kvnr = null, partnernr = "P000000004", personId = "P000000004")

            outcome.shouldBeInstanceOf<ToolOutcome.Completed.Identified>()
            outcome.claims shouldBe listOf(
                Claim(AttributeType.PERSON_ID, "P000000004", ClaimSource.PERSON_DIRECTORY, IdentKvnrDescriptor.maxAcr)
            )
        }

        then("an unknown Partnernummer fails without saying whether it exists") {
            val outcome = handler.patch(toolSessionId, kvnr = null, partnernr = "P999999999", personId = null)

            outcome.shouldBeInstanceOf<ToolOutcome.Failed>()
            outcome.reason.template shouldBe "Partnernummer konnte nicht zugeordnet werden"
        }
    }

    given("a KVNR the register does not know") {
        then("it fails with a message that does not reveal whether the number exists") {
            val outcome = handler.patch(toolSessionId, "X999999999", partnernr = null, personId = null)

            outcome.shouldBeInstanceOf<ToolOutcome.Failed>()
            outcome.reason.template shouldBe "Versichertennummer konnte nicht zugeordnet werden"
        }
    }

    given("no KVNR submitted yet") {
        then("it keeps asking for one") {
            val outcome = handler.patch(toolSessionId, kvnr = null, partnernr = null, personId = null)

            outcome.shouldBeInstanceOf<ToolOutcome.InProgress>()
            outcome.nextStep shouldBe "input"
        }
    }

    given("the descriptor") {
        then("it declares itself a correlation step - stated, not inferred from an empty factor set") {
            IdentKvnrDescriptor.role shouldBe MethodRole.CORRELATION
            IdentKvnrDescriptor.role.category shouldBe ToolCategory.IDENT
        }

        then("it is only offerable once an attestation established the identity to match against") {
            IdentKvnrDescriptor.requires.map { it.attributeType } shouldBe
                listOf(AttributeType.NAME, AttributeType.VORNAME, AttributeType.GEBURTSDATUM)
        }
    }
})
