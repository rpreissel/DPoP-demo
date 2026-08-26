package com.example.dpop.id_fsc

import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.MethodFamily
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.ToolDescriptor
import org.springframework.stereotype.Component

/** No sibling today (ident-fsc is the only tool for "fsc") - kept as its own value for the same uniform shape every other module follows. */
internal val FSC_METHOD = MethodFamily("fsc")

/**
 * Self-description for toolId=ident-fsc (docs/03-tool-architektur.md #1) - its own small bean so
 * IdentFscToolHandler stays pure business logic and can move to `internal` (DPoP-demo-vun).
 * Kotlin `object` + `@Component` is recognized by Spring as a singleton bean without reflection
 * (Spring Framework 5.3+).
 */
@Component
object IdentFscDescriptor : ToolDescriptor {
    override val toolId = "ident-fsc"
    override val role = MethodRole.IDENTIFICATION
    override val methodFamily = FSC_METHOD
    override val factorTypes = setOf(FactorType.POSSESSION)
    override val maxAcr = "loa2"
}
