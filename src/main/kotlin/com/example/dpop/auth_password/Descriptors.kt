package com.example.dpop.auth_password

import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.ToolDescriptor
import org.springframework.stereotype.Component

/** Shared by enroll-password/auth-password/auth-password-lookup - the one place "password" is spelled out. */
internal const val PASSWORD_METHOD = "password"

/**
 * Self-description for every auth_password tool (docs/03-tool-architektur.md #1), one bean per
 * toolId, kept in a single file since none of them carry state or dependencies - Kotlin
 * `object` + `@Component` is recognized by Spring as a singleton bean without reflection
 * (Spring Framework 5.3+). This lets the handlers stay pure business logic and move to
 * `internal` (DPoP-demo-vun).
 */
@Component
object EnrollPasswordDescriptor : ToolDescriptor {
    override val toolId = "enroll-password"
    override val role = MethodRole.ENROLLMENT
    override val method = PASSWORD_METHOD
    override val factorTypes = setOf(FactorType.KNOWLEDGE)
    override val maxAcr = "loa1"
    // No identifier field: the account's confirmed email is the identifier, so this tool only
    // ever asks for the password itself.
    override val requiresConfirmedEmail = true
}

@Component
object AuthPasswordUseDescriptor : ToolDescriptor {
    override val toolId = "auth-password"
    override val role = MethodRole.DEVICE_AUTH
    override val method = PASSWORD_METHOD
    override val factorTypes = setOf(FactorType.KNOWLEDGE)
    override val maxAcr = "loa1"
}

@Component
object AuthPasswordLookupDescriptor : ToolDescriptor {
    override val toolId = "auth-password-lookup"
    override val role = MethodRole.LOOKUP_AUTH
    override val method = PASSWORD_METHOD
    override val factorTypes = setOf(FactorType.KNOWLEDGE)
    override val maxAcr = "loa1"
}
