package com.example.dpop.id_kvnr

import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.ClaimDeclaration
import com.example.dpop.tool_spi.ClaimRequirement
import com.example.dpop.tool_spi.ClaimSource
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ToolId
import com.example.dpop.tool_spi.TrustLevel
import org.springframework.stereotype.Component

/** No sibling (ident-kvnr is the only tool for "kvnr") - kept as its own value for the same uniform shape every other module follows. */
internal const val KVNR_METHOD = "kvnr"

/**
 * Self-description for toolId=ident-kvnr: the correlation half of an identification
 * (docs/12-entscheidungen.md ADR-18). An attestation like `ident-eid` establishes WHO someone is;
 * this tool answers whether that person is in the register, and binds the `PERSON_ID` anchor.
 *
 * [claims] rest on `EXT_STAMMDATEN`, not on this tool: the register supplies both values, this
 * tool is only its channel - the same relationship `ident-fsc` has to the master data.
 *
 * [MethodRole.CORRELATION] states what this is: [maxAcr] is `loa2` although typing a KVNR proves
 * nothing by itself. The assurance comes from
 * [requires] plus the identity match the account module performs before the anchor is written -
 * the same shape as `enroll-password`, which leans on `ClaimRequirement(EMAIL, PROVEN)` instead
 * of proving the address itself. The session's own IAL is unaffected either way: an attestation
 * that reached `loa3` keeps it, because `resolveAcr` takes the maximum over the IDENTITY axis.
 */
@Component
object IdentKvnrDescriptor : ToolDescriptor {
    override val toolId = ToolId("ident-kvnr")
    override val role = MethodRole.CORRELATION
    override val method = KVNR_METHOD
    override val factorTypes = emptySet<com.example.dpop.tool_spi.FactorType>()
    override val maxAcr = AcrLevel.LOA2
    override val claims = setOf(
        ClaimDeclaration(AttributeType.PERSON_ID, ClaimSource.EXT_STAMMDATEN),
        ClaimDeclaration(AttributeType.KVNR, ClaimSource.EXT_STAMMDATEN)
    )

    // Only offerable once an attestation established who the subject is - there must be
    // something to match the register's person against, or this would degrade into "type any
    // KVNR and own that person". Deliberately expressed as the attributes themselves, not as
    // "ident-eid must have run": an EUDI wallet attesting the same three satisfies it unchanged.
    override val requires = setOf(
        ClaimRequirement(AttributeType.NAME, TrustLevel.PROVEN),
        ClaimRequirement(AttributeType.VORNAME, TrustLevel.PROVEN),
        ClaimRequirement(AttributeType.GEBURTSDATUM, TrustLevel.PROVEN)
    )
}
