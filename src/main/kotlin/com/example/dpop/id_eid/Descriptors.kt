package com.example.dpop.id_eid

import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.ClaimDeclaration
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ToolId
import com.example.dpop.tool_spi.ClaimSource
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
    override val toolId = ToolId("ident-eid")
    override val role = MethodRole.IDENTIFICATION
    override val method = EID_METHOD
    override val factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)
    override val maxAcr = AcrLevel.LOA3
    // The attributes a successful run asserts, each declared with the anchor it is asserted
    // with: the values are read from the eID card, so they rest on this procedure's own
    // authority (ClaimSource.of(toolId)) - ext_stammdaten is only cross-checked for
    // consistency, it is not the value's source (unlike ident-fsc, where the master-data
    // backend IS the source and the tool is only its channel). Address fields
    // (strasse/hausnummer/plz/ort) are deliberately not claims: no anchor or projection
    // consumer exists for them, so they stay in the auditDetails blob.
    override val claims = setOf(
        ClaimDeclaration(AttributeType.PERSON_ID, ClaimSource.of(toolId)),
        ClaimDeclaration(AttributeType.KVNR, ClaimSource.of(toolId)),
        ClaimDeclaration(AttributeType.NAME, ClaimSource.of(toolId)),
        ClaimDeclaration(AttributeType.VORNAME, ClaimSource.of(toolId)),
        ClaimDeclaration(AttributeType.GEBURTSDATUM, ClaimSource.of(toolId))
    )
}
