package com.example.dpop.orchestrator.tool

import com.example.dpop.id_eid.IdentEidDescriptor
import com.example.dpop.id_fsc.IdentFscDescriptor
import com.example.dpop.id_nect.IdentNectDescriptor
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.ToolDescriptor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain

/**
 * ADR-39: every identification is findable again by name, first name and date of birth - the
 * change log's search key - so every identification procedure has to deliver all three. A
 * procedure that does not is refused at startup.
 */
class IdentificationFindableRuleTest : BehaviorSpec({

    given("the real identification procedures") {
        then("all three deliver name, first name and date of birth, so the catalog starts") {
            ToolHandlerRegistry(listOf(IdentFscDescriptor, IdentEidDescriptor, IdentNectDescriptor))
        }
    }

    given("an identification procedure without a date of birth") {
        val withoutBirthDate = object : ToolDescriptor by IdentFscDescriptor {
            override val claims = IdentFscDescriptor.claims.filterNot { it.attributeType == AttributeType.GEBURTSDATUM }.toSet()
        }
        then("the registry refuses to start") {
            shouldThrow<IllegalStateException> { ToolHandlerRegistry(listOf(withoutBirthDate)) }.message shouldContain "GEBURTSDATUM"
        }
    }
})
