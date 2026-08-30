package com.example.dpop.auth_device

import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.ToolDescriptor
import org.springframework.stereotype.Component

/** Shared by enroll-device/auth-device - the one place "device" is spelled out. */
internal const val DEVICE_METHOD = "device"

/**
 * The `details` map key [AuthDeviceDescriptor.matchesCaller] reads and
 * [com.example.dpop.auth_device.internal.EnrollDeviceToolHandler] writes - private to this
 * module, never referenced by tool_spi or the orchestrator (see [ToolDescriptor.matchesCaller]).
 */
internal const val DEVICE_BINDING_KEY_REF = "deviceBindingKeyRef"

/** The [com.example.dpop.tool_spi.EnrollmentRef.type] enroll-device writes and auth-device reads back - the one place it is spelled out. */
internal const val DEVICE_ENROLLMENT_TYPE = "device_enrollment"

/**
 * Self-description for every auth_device tool (docs/03-tool-architektur.md #1), one bean per
 * toolId, kept in a single file since none of them carry state or dependencies - Kotlin
 * `object` + `@Component` is recognized by Spring as a singleton bean without reflection
 * (Spring Framework 5.3+). This lets the handlers stay pure business logic and move to
 * `internal` (DPoP-demo-vun).
 */
@Component
object AuthDeviceDescriptor : ToolDescriptor {
    override val toolId = "auth-device"
    override val role = MethodRole.DEVICE_AUTH
    override val method = DEVICE_METHOD
    override val factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE, FactorType.INHERENCE)
    override val maxAcr = "loa2"
    // Declared independently per tool variant, same as maxAcr/factorTypes - not a fact that must
    // be identical across every tool sharing this method (a future LOOKUP_AUTH sibling, for
    // instance, could legitimately answer differently). Callers that need this for a SPECIFIC
    // tool resolve its own descriptor unambiguously by (method, role) - MethodRole, unlike
    // category, fully distinguishes DEVICE_AUTH from LOOKUP_AUTH - never an arbitrary descriptor
    // picked by method name alone (DefaultAuthPolicy.candidateTools).
    override val allowsMultipleInstances = true

    /**
     * Reads [DEVICE_BINDING_KEY_REF], the same key
     * [com.example.dpop.auth_device.internal.EnrollDeviceToolHandler] writes into `auditDetails`.
     * Generic multi-instance resolution (CandidateTools/DefaultAuthPolicy) calls this without
     * ever knowing the key name itself.
     */
    override fun matchesCaller(details: Map<String, Any?>?, callerBindingKeyRef: String): Boolean =
        details?.get(DEVICE_BINDING_KEY_REF) == callerBindingKeyRef
}

/**
 * maxAcr=loa2 and factorTypes cover both possible access-means outcomes (pin=KNOWLEDGE,
 * biometric=INHERENCE, plus POSSESSION of the key itself) because a single successful run
 * already combines two factor types on its own - the same "hypothetical passkey with user
 * verification" case docs/03-tool-architektur.md already names.
 */
@Component
object EnrollDeviceDescriptor : ToolDescriptor {
    override val toolId = "enroll-device"
    override val role = MethodRole.ENROLLMENT
    override val method = DEVICE_METHOD
    override val factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE, FactorType.INHERENCE)
    override val maxAcr = "loa2"
    override val allowsMultipleInstances = true
}
