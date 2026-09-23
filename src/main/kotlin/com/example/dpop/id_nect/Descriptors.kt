package com.example.dpop.id_nect

import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.ClaimDeclaration
import com.example.dpop.tool_spi.ClaimSource
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ToolId
import org.springframework.stereotype.Component

internal const val NECT_METHOD = "nect"

/**
 * Self-description for toolId=ident-nect. The user leaves the app for Nect's jump page and picks
 * a document there - eID, ePass or EUDI wallet - so [maxAcr] and [factorTypes] are the ceiling
 * over all three; each run reports what its document actually proved (IdentNectToolHandler).
 *
 * Attests what the document shows, on this procedure's own authority, and resolves nobody - the
 * same role as `ident-eid` (ADR-18): a document carries no KVNR, binding to a register person is
 * `ident-kvnr`'s act. Declared claims are the union; a run reports a subset (a passport has no
 * address, a wallet shares only what its holder released).
 */
@Component
object IdentNectDescriptor : ToolDescriptor {
    override val toolId = ToolId("ident-nect")
    override val role = MethodRole.IDENTIFICATION
    override val method = NECT_METHOD
    override val factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE, FactorType.INHERENCE)
    override val maxAcr = AcrLevel.LOA3
    // Nothing is typed here: the run opens on "go to Nect".
    override val startStep = "redirect"
    override val claims = setOf(
        ClaimDeclaration(AttributeType.NAME, ClaimSource.of(toolId)),
        ClaimDeclaration(AttributeType.VORNAME, ClaimSource.of(toolId)),
        ClaimDeclaration(AttributeType.GEBURTSDATUM, ClaimSource.of(toolId)),
        ClaimDeclaration(AttributeType.STRASSE, ClaimSource.of(toolId)),
        ClaimDeclaration(AttributeType.HAUSNUMMER, ClaimSource.of(toolId)),
        ClaimDeclaration(AttributeType.PLZ, ClaimSource.of(toolId)),
        ClaimDeclaration(AttributeType.ORT, ClaimSource.of(toolId)),
        // eID via Nect reads the same card pseudonym as ident-eid - the same person's card is
        // recognized whichever of the two read it (ADR-19).
        ClaimDeclaration(AttributeType.EID_RESTRICTED_ID, ClaimSource.of(toolId))
    )
}
