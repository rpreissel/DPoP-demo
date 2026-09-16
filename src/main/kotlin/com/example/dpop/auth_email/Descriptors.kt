package com.example.dpop.auth_email

import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.ClaimDeclaration
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ToolId
import com.example.dpop.tool_spi.ClaimSource
import org.springframework.stereotype.Component

/** Shared by enroll-email/auth-email/auth-email-lookup - the one place "email" is spelled out. */
internal const val EMAIL_METHOD = "email"

/**
 * Self-description for every auth_email tool (docs/03-tool-architektur.md #1), one bean per
 * toolId, kept in a single file since none of them carry state or dependencies - Kotlin
 * `object` + `@Component` is recognized by Spring as a singleton bean without reflection
 * (Spring Framework 5.3+). This lets the handlers stay pure business logic and move to
 * `internal`.
 */
@Component
object EnrollEmailDescriptor : ToolDescriptor {
    override val toolId = ToolId("enroll-email")
    override val role = MethodRole.ENROLLMENT
    override val method = EMAIL_METHOD
    override val factorTypes = setOf(FactorType.KNOWLEDGE)
    override val maxAcr = AcrLevel.LOA1
    // The confirmed address this enrollment asserts about its subject, carried as a claim on
    // Completed.Enrolled - the generic Action.AdoptCredential handling records it
    // (AccountService.recordClaim), which consolidates the account.email projection, its anchor
    // and the AccountChanged event in one write. The proof is this tool's own code exchange,
    // hence the tool itself as trust anchor.
    override val claims = setOf(ClaimDeclaration(AttributeType.EMAIL, ClaimSource.of(toolId)))
}

@Component
object AuthEmailUseDescriptor : ToolDescriptor {
    override val toolId = ToolId("auth-email")
    override val role = MethodRole.IDENTIFIED_AUTH
    override val method = EMAIL_METHOD
    override val factorTypes = setOf(FactorType.KNOWLEDGE)
    override val maxAcr = AcrLevel.LOA1
}

@Component
object AuthEmailLookupDescriptor : ToolDescriptor {
    override val toolId = ToolId("auth-email-lookup")
    override val role = MethodRole.LOOKUP_AUTH
    override val method = EMAIL_METHOD
    override val factorTypes = setOf(FactorType.KNOWLEDGE)
    override val maxAcr = AcrLevel.LOA1
}
