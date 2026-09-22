package com.example.dpop.auth_password

import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.TrustLevel
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.ClaimDeclaration
import com.example.dpop.tool_spi.ClaimRequirement
import com.example.dpop.tool_spi.ClaimSource
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_api.PasswordCredentialPort
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ToolId
import org.springframework.stereotype.Component

/**
 * Shared by enroll-password/auth-password/auth-password-lookup - still the one place "password" is
 * spelled out, now that another module needs the same name to resolve this credential: the literal
 * lives on [PasswordCredentialPort.METHOD], this module's own outward-facing declaration of it,
 * and is read from there rather than repeated.
 */
internal const val PASSWORD_METHOD = PasswordCredentialPort.METHOD

/** The [com.example.dpop.tool_spi.EnrollmentRef.type] enroll-password writes and auth-password/auth-password-lookup read back - the one place it is spelled out. */
internal const val PASSWORD_ENROLLMENT_TYPE = "auth_password.enrollment"

/**
 * Self-description for every auth_password tool (docs/03-tool-architektur.md #1), one bean per
 * toolId, kept in a single file since none of them carry state or dependencies - Kotlin
 * `object` + `@Component` is recognized by Spring as a singleton bean without reflection
 * (Spring Framework 5.3+). This lets the handlers stay pure business logic and move to
 * `internal`.
 */
@Component
object EnrollPasswordDescriptor : ToolDescriptor {
    override val toolId = ToolId("enroll-password")
    override val role = MethodRole.ENROLLMENT
    override val method = PASSWORD_METHOD
    override val factorTypes = setOf(FactorType.KNOWLEDGE)
    override val maxAcr = AcrLevel.LOA1
    // No identifier field: the account's confirmed email is the identifier, so this tool only
    // ever asks for the password itself - and is only offered once that email is proven.
    override val requires = setOf(ClaimRequirement(AttributeType.EMAIL, TrustLevel.PROVEN))

    /**
     * States the bare fact that this account now has a password, so that another method can make
     * itself depend on it through the ordinary `requires` gate ([AttributeType.PASSWORD_EXISTS]).
     * It needs no value of its own - and gets its lifetime for free: `retractClaimsOf` retracts
     * every MethodModule claim of a revoked instance, so removing the password un-establishes
     * this by itself, and whatever required it falls with it.
     */
    override val claims = setOf(ClaimDeclaration(AttributeType.PASSWORD_EXISTS, ClaimSource.of(toolId)))
}

@Component
object AuthPasswordUseDescriptor : ToolDescriptor {
    override val toolId = ToolId("auth-password")
    override val role = MethodRole.IDENTIFIED_AUTH
    override val method = PASSWORD_METHOD
    override val factorTypes = setOf(FactorType.KNOWLEDGE)
    override val maxAcr = AcrLevel.LOA1
}

@Component
object AuthPasswordLookupDescriptor : ToolDescriptor {
    override val toolId = ToolId("auth-password-lookup")
    override val role = MethodRole.LOOKUP_AUTH
    override val method = PASSWORD_METHOD
    override val factorTypes = setOf(FactorType.KNOWLEDGE)
    override val maxAcr = AcrLevel.LOA1
}
