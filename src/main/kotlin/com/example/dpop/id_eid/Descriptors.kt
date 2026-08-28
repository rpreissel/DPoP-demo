package com.example.dpop.id_eid

import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.ToolDescriptor
import org.springframework.stereotype.Component

/** No sibling today (ident-eid is the only tool for "eid") - kept as its own value for the same uniform shape every other module follows. */
internal const val EID_METHOD = "eid"

/**
 * Self-description for toolId=ident-eid - its own small bean so IdentEidToolHandler stays pure
 * business logic. Kotlin `object` + `@Component` is recognized by Spring as a singleton bean
 * without reflection (Spring Framework 5.3+).
 *
 * Mock eID: reads a simulated card (possession) plus a PIN (knowledge) in one run, so
 * `maxAcr=loa3` and both factor types are claimed - unlike `ident-fsc`, which only ever proves
 * possession of the mailed code.
 */
@Component
object IdentEidDescriptor : ToolDescriptor {
    override val toolId = "ident-eid"
    override val role = MethodRole.IDENTIFICATION
    override val method = EID_METHOD
    override val factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)
    override val maxAcr = "loa3"
}
