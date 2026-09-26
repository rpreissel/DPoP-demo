package com.example.dpop.id_fsc

import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.ClaimDeclaration
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ToolId
import com.example.dpop.tool_spi.ClaimSource
import org.springframework.stereotype.Component

/** No sibling today (ident-fsc is the only tool for "fsc") - kept as its own value for the same uniform shape every other module follows. */
internal const val FSC_METHOD = "fsc"

/**
 * Self-description for toolId=ident-fsc (docs/03-tool-architektur.md #1) - its own small bean so
 * IdentFscToolHandler stays pure business logic and can move to `internal`.
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
    // The attributes a successful run asserts, each declared with the anchor it is asserted
    // with - all of them are checked against the master-data backend via the FSC channel,
    // hence ClaimSource.PERSON_DIRECTORY: the FSC is the master-data backend's own delivery
    // channel, this tool is only the kanal, never the value's source. The handler's reported
    // claims must match this declaration (assertClaimsCovered enforces it on adoption).
    override val claims = setOf(
        ClaimDeclaration(AttributeType.PERSON_ID, ClaimSource.PERSON_DIRECTORY),
        ClaimDeclaration(AttributeType.KVNR, ClaimSource.PERSON_DIRECTORY),
        ClaimDeclaration(AttributeType.VERSNR, ClaimSource.PERSON_DIRECTORY),
        ClaimDeclaration(AttributeType.NAME, ClaimSource.PERSON_DIRECTORY),
        ClaimDeclaration(AttributeType.VORNAME, ClaimSource.PERSON_DIRECTORY),
        ClaimDeclaration(AttributeType.GEBURTSDATUM, ClaimSource.PERSON_DIRECTORY)
    )
}
