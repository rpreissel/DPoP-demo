package com.example.dpop.orchestrator.tool

import com.example.dpop.id_eid.IdentEidDescriptor
import com.example.dpop.id_fsc.IdentFscDescriptor
import com.example.dpop.id_kvnr.IdentKvnrDescriptor
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.ClaimDeclaration
import com.example.dpop.tool_spi.ClaimSource
import com.example.dpop.tool_spi.ToolDescriptor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain

/**
 * Review 2026-09, Phase F: identity matching trusts a KVNR only because the Personenverzeichnis
 * vouched for it. A tool that declares one from anywhere else is refused at startup - the unchecked
 * path it would need no longer exists.
 */
class KvnrSourceRuleTest : BehaviorSpec({

    given("the real catalog") {
        then("only Personenverzeichnis-backed tools declare a KVNR, so it starts") {
            ToolHandlerRegistry(listOf(IdentFscDescriptor, IdentKvnrDescriptor, IdentEidDescriptor))
        }
    }

    given("a tool that reads a KVNR itself") {
        val cardReader = object : ToolDescriptor by IdentEidDescriptor {
            override val claims = IdentEidDescriptor.claims + ClaimDeclaration(AttributeType.KVNR, ClaimSource.of(IdentEidDescriptor.toolId))
        }
        then("the registry refuses to start") {
            shouldThrow<IllegalStateException> { ToolHandlerRegistry(listOf(cardReader)) }.message shouldContain "Personenverzeichnis"
        }
    }
})
