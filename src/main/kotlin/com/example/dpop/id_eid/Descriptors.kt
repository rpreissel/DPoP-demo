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
 * possession of the mailed code. It resolves nobody: binding the attested identity to a register
 * person is `ident-kvnr`'s separate act (docs/12-entscheidungen.md ADR-18).
 */
@Component
object IdentEidDescriptor : ToolDescriptor {
    override val toolId = ToolId("ident-eid")
    override val role = MethodRole.IDENTIFICATION
    override val method = EID_METHOD
    override val factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)
    override val maxAcr = AcrLevel.LOA3
    // Not the role's "input": nothing is typed to begin with, the run opens on the card read.
    override val startStep = "card"
    // Exactly what the card carries, on this procedure's own authority
    // (ClaimSource.of(toolId)) - unlike ident-fsc, where the master-data backend IS the source
    // and the tool is only its channel. Deliberately no PERSON_ID and no KVNR: a real eID card
    // holds neither, and claiming them here would mean vouching for values this procedure never
    // read (ADR-18). Address fields (strasse/hausnummer/plz/ort) are not claims either: no
    // anchor or projection consumer exists for them, so they stay in the auditDetails blob.
    override val claims = setOf(
        ClaimDeclaration(AttributeType.NAME, ClaimSource.of(toolId)),
        ClaimDeclaration(AttributeType.VORNAME, ClaimSource.of(toolId)),
        ClaimDeclaration(AttributeType.GEBURTSDATUM, ClaimSource.of(toolId))
    )
}
