package com.example.dpop.auth_sms

import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.ToolDescriptor
import org.springframework.stereotype.Component

/** Shared by enroll-sms/auth-sms/auth-sms-lookup - the one place "sms" is spelled out. */
internal const val SMS_METHOD = "sms"

/** The [com.example.dpop.tool_spi.EnrollmentRef.type] enroll-sms writes and auth-sms/auth-sms-lookup read back - the one place it is spelled out. */
internal const val SMS_ENROLLMENT_TYPE = "auth_sms_enrollment"

/**
 * Self-description for every auth_sms tool (docs/03-tool-architektur.md #1), one bean per
 * toolId, kept in a single file since none of them carry state or dependencies - Kotlin
 * `object` + `@Component` is recognized by Spring as a singleton bean without reflection
 * (Spring Framework 5.3+). This lets the handlers stay pure business logic and move to
 * `internal` (DPoP-demo-vun); ToolHandlerRegistry still just collects `List<ToolDescriptor>`
 * straight from Spring, unaffected by how many files or beans that comes from.
 */
@Component
object EnrollSmsDescriptor : ToolDescriptor {
    override val toolId = "enroll-sms"
    override val role = MethodRole.ENROLLMENT
    override val method = SMS_METHOD
    override val factorTypes = setOf(FactorType.POSSESSION)
    override val maxAcr = "loa1"
}

@Component
object AuthSmsUseDescriptor : ToolDescriptor {
    override val toolId = "auth-sms"
    override val role = MethodRole.DEVICE_AUTH
    override val method = SMS_METHOD
    override val factorTypes = setOf(FactorType.POSSESSION)
    override val maxAcr = "loa1"
}

@Component
object AuthSmsLookupDescriptor : ToolDescriptor {
    override val toolId = "auth-sms-lookup"
    override val role = MethodRole.LOOKUP_AUTH
    override val method = SMS_METHOD
    override val factorTypes = setOf(FactorType.POSSESSION)
    override val maxAcr = "loa1"
}
