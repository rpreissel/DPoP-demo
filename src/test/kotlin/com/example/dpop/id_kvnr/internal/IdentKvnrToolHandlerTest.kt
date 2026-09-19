package com.example.dpop.id_kvnr.internal

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
 * person reference and KVNR, both under `EXT_STAMMDATEN` - unlike `ident-eid`, which vouches for
 * the card's attributes itself. The identity match that makes this safe lives in the account
 * module ([com.example.dpop.account.internal.IdentityMatchingServiceTest]), not here: this tool
 * never sees accounts.
 */
class IdentKvnrToolHandlerTest : BehaviorSpec({

    val toolSessionId = UUID.randomUUID()
    val repository = mockk<IdKvnrToolSessionRepository>()
    val handler = IdentKvnrToolHandler(IdentKvnrDescriptor, repository)
    val data = IdKvnrToolSession(toolSessionId = toolSessionId)

    beforeTest {
        every { repository.findById(toolSessionId) } returns Optional.of(data)
        every { repository.save(any()) } returns data
    }

    given("a KVNR the register resolves") {
        then("it asserts the person reference and the number, both vouched for by the register") {
            val outcome = handler.patch(toolSessionId, "A123456789", personId = 42L)

            outcome.shouldBeInstanceOf<ToolOutcome.Completed.Identified>()
            outcome.claims shouldBe listOf(
                Claim(AttributeType.PERSON_ID, "42", ClaimSource.EXT_STAMMDATEN, IdentKvnrDescriptor.maxAcr),
                Claim(AttributeType.KVNR, "A123456789", ClaimSource.EXT_STAMMDATEN, IdentKvnrDescriptor.maxAcr)
            )
        }
    }

    given("a KVNR the register does not know") {
        then("it fails with a message that does not reveal whether the number exists") {
            val outcome = handler.patch(toolSessionId, "X999999999", personId = null)

            outcome.shouldBeInstanceOf<ToolOutcome.Failed>()
            outcome.reason shouldBe "Versichertennummer konnte nicht zugeordnet werden"
        }
    }

    given("no KVNR submitted yet") {
        then("it keeps asking for one") {
            val outcome = handler.patch(toolSessionId, kvnr = null, personId = null)

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
