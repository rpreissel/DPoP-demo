package com.example.dpop.id_fsc

import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ToolId
import org.springframework.stereotype.Component

/** No sibling today (ident-fsc is the only tool for "fsc") - kept as its own value for the same uniform shape every other module follows. */
internal const val FSC_METHOD = "fsc"

/**
 * Self-description for toolId=ident-fsc (docs/03-tool-architektur.md #1) - its own small bean so
 * IdentFscToolHandler stays pure business logic and can move to `internal` (DPoP-demo-vun).
 * Kotlin `object` + `@Component` is recognized by Spring as a singleton bean without reflection
 * (Spring Framework 5.3+).
 */
@Component
object IdentFscDescriptor : ToolDescriptor {
    override val toolId = ToolId("ident-fsc")
    override val role = MethodRole.IDENTIFICATION
    override val method = FSC_METHOD
    override val factorTypes = setOf(FactorType.POSSESSION)
    override val maxAcr = AcrLevel.LOA2
}
