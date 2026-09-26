package com.example.dpop.auth_email

import com.example.dpop.texts.Text
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.ClaimDeclaration
import com.example.dpop.tool_spi.ClaimRequirement
import com.example.dpop.tool_spi.TrustLevel
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ToolId
import com.example.dpop.tool_spi.ClaimSource
import org.springframework.stereotype.Component

/** Shared by confirm-email/enroll-email/auth-email/auth-email-lookup - the one place "email" is spelled out. */
internal const val EMAIL_METHOD = "email"

/**
 * Self-description for every auth_email tool (docs/03-tool-architektur.md #1), one bean per
 * toolId, kept in a single file since none of them carry state or dependencies - Kotlin
 * `object` + `@Component` is recognized by Spring as a singleton bean without reflection
 * (Spring Framework 5.3+). This lets the handlers stay pure business logic and move to
 * `internal`.
 *
 * Two tools rather than one (ADR-17): [ConfirmEmailDescriptor] establishes the address
 * as account infrastructure, [EnrollEmailDescriptor] turns it into a login method.
 */

/**
 * Proves the subject controls an address, and nothing else: the account keeps the confirmed value
 * as its EMAIL anchor, no credential is created and no method appears. That the account HAS a
 * confirmed address is infrastructure - three lookup tools resolve an account through it and
 * `enroll-password` is gated on it - while "log in with an emailed code" is a separate decision
 * ([EnrollEmailDescriptor]).
 *
 * Hence [MethodRole.ATTESTATION] and no [factorTypes]: a confirmed address is not a factor anyone
 * authenticated with, and `Completed.Attested` reports no `amr` so it cannot raise this channel's
 * assurance.
 */
@Component
object ConfirmEmailDescriptor : ToolDescriptor {
    override val toolId = ToolId("confirm-email")
    override val role = MethodRole.ATTESTATION
    override val method = EMAIL_METHOD
    override val factorTypes = emptySet<FactorType>()
    override val maxAcr = AcrLevel.LOA1
    // The code exchange itself IS the proof, hence this tool as the claim's own trust anchor
    // (unlike an IDENT tool, which is only the register's channel - docs/12-entscheidungen.md).
    override val claims = setOf(ClaimDeclaration(AttributeType.EMAIL, ClaimSource.of(toolId)))
}

/**
 * Turns an ALREADY confirmed address into an authentication method - no code exchange, because
 * control over the address was proven by [ConfirmEmailDescriptor] and proving it twice adds
 * nothing. One shot: activating the tool completes it.
 *
 * Reports no `amr` for the same reason: nothing was proven in this run, so this enrollment must
 * not raise the channel's assurance the way `enroll-sms`'s own TAN check legitimately does.
 */
@Component
object EnrollEmailDescriptor : ToolDescriptor {
    override val toolId = ToolId("enroll-email")
    override val role = MethodRole.ENROLLMENT
    override val method = EMAIL_METHOD
    override val factorTypes = setOf(FactorType.KNOWLEDGE)
    override val maxAcr = AcrLevel.LOA1
    override val requires = setOf(ClaimRequirement(AttributeType.EMAIL, TrustLevel.PROVEN))
    override val completesOnActivation = Text("Ihre bereits bestätigte E-Mail-Adresse wird sofort zum Anmeldeverfahren. Einen Code brauchen Sie dafür nicht.")
}

@Component
object AuthEmailDescriptor : ToolDescriptor {
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
