package com.example.dpop.auth_email

import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.ToolDescriptor
import org.springframework.stereotype.Component

/** Shared by enroll-email/auth-email/auth-email-lookup - the one place "email" is spelled out. */
internal const val EMAIL_METHOD = "email"

/**
 * Self-description for every auth_email tool (docs/03-tool-architektur.md #1), one bean per
 * toolId, kept in a single file since none of them carry state or dependencies - Kotlin
 * `object` + `@Component` is recognized by Spring as a singleton bean without reflection
 * (Spring Framework 5.3+). This lets the handlers stay pure business logic and move to
 * `internal` (DPoP-demo-vun).
 */
@Component
object EnrollEmailDescriptor : ToolDescriptor {
    override val toolId = "enroll-email"
    override val role = MethodRole.ENROLLMENT
    override val method = EMAIL_METHOD
    override val factorTypes = setOf(FactorType.POSSESSION)
    override val maxAcr = "loa1"
    override val confirmsAccountEmail = true
}

@Component
object AuthEmailUseDescriptor : ToolDescriptor {
    override val toolId = "auth-email"
    override val role = MethodRole.DEVICE_AUTH
    override val method = EMAIL_METHOD
    override val factorTypes = setOf(FactorType.POSSESSION)
    override val maxAcr = "loa1"
}

@Component
object AuthEmailLookupDescriptor : ToolDescriptor {
    override val toolId = "auth-email-lookup"
    override val role = MethodRole.LOOKUP_AUTH
    override val method = EMAIL_METHOD
    override val factorTypes = setOf(FactorType.POSSESSION)
    override val maxAcr = "loa1"
}
