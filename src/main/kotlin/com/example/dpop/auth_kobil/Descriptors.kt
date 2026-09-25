package com.example.dpop.auth_kobil

import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.DemoOnly
import com.example.dpop.tool_spi.CallerKeyBinding
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.InstanceDisclosure
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ToolId
import org.springframework.stereotype.Component

/** Shared by enroll-kobil/auth-kobil - the one place "kobil" is spelled out. */
internal const val KOBIL_METHOD = "kobil"

/** The [com.example.dpop.tool_spi.EnrollmentRef.type] enroll-kobil writes and auth-kobil reads back. */
internal const val KOBIL_ENROLLMENT_TYPE = "auth_kobil.enrollment"

/**
 * The `details` map key [AuthKobilDescriptor.keyBinding] reads and the enroll handler writes -
 * private to this module (see [ToolDescriptor.keyBinding]). It holds the enrolling channel's DPoP
 * binding key: at OFFER time that is the only thing known about the caller, so it is what decides
 * whether this credential may even be proposed.
 */
internal const val KOBIL_BINDING_KEY_REF = "kobilBindingKeyRef"

/**
 * The KOBIL device identifier ("Kennung") in `auditDetails`. Deliberately a second, differently
 * named constant rather than a second use of [KOBIL_BINDING_KEY_REF]: it answers a different
 * question at a different time. The server only learns it after redeeming an OTP, so it can never
 * be an offering filter - it is the anchor a redeemed assertion is *compared against*.
 */
internal const val KOBIL_DEVICE_ID = "kobilDeviceId"

/**
 * The credential lives on one physical phone: the KOBIL activation is bound to that device, and
 * the local unlock secret sits in that app installation. Offering it on another device would
 * propose something the caller cannot possibly complete - hence the same
 * `allowsMultipleInstances` + `keyBinding` pair `auth_device` uses, and with it the rebinding
 * revocation `JourneyActionExecutor` already applies to every key-bound credential.
 */
private val KOBIL_KEY_BINDING = CallerKeyBinding { instanceDetails, callerBindingKeyRef ->
    instanceDetails?.get(KOBIL_BINDING_KEY_REF) == callerBindingKeyRef
}

/**
 * What this method shows about one of its instances: the identifier KOBIL gave that phone - the
 * provider's own side of the binding, which the client cannot learn any other way (it never
 * travels through an assertion the client gets to see). Reads [KOBIL_DEVICE_ID], which stays
 * private to this module; the orchestrator asks this function, never the detail map
 * ([ToolDescriptor.instanceDisclosure]).
 */
private val KOBIL_INSTANCE_DISCLOSURE = InstanceDisclosure { it?.get(KOBIL_DEVICE_ID) as? String }

/**
 * Self-description for every auth_kobil tool (docs/03-tool-architektur.md #1), one bean per
 * toolId.
 *
 * `factorTypes`/`maxAcr` are declared identically on both tools on purpose, exactly as
 * `auth_device` does: `DefaultAuthPolicy.descriptorFor` and `ChannelService.toActiveMethodViews`
 * resolve a method's ceiling by method name alone and would otherwise pick an arbitrary sibling.
 */
@Component
object EnrollKobilDescriptor : ToolDescriptor {
    override val toolId = ToolId("enroll-kobil")
    override val role = MethodRole.ENROLLMENT
    override val method = KOBIL_METHOD
    /** Not the role default `enroll`: the one thing the client does here is run the SDK's activation. */
    override val startStep = "activate"
    override val factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE, FactorType.INHERENCE)
    override val maxAcr = AcrLevel.LOA2
    override val demoOnly = SIMULATED_KOBIL
    override val allowsMultipleInstances = true
    override val keyBinding = KOBIL_KEY_BINDING
    override val instanceDisclosure = KOBIL_INSTANCE_DISCLOSURE
}

/**
 * maxAcr=loa2 and factorTypes cover both access-means outcomes (pin=KNOWLEDGE,
 * biometric=INHERENCE, plus POSSESSION of the KOBIL-bound device itself) because a single
 * successful run already combines two factor types - the same reading `auth_device` applies.
 *
 * The possession half is stronger here than anywhere else in this system: it is not a client
 * self-signed proof but an assertion this backend fetches from KOBIL itself, the client having
 * carried only a one-time reference. The access-means half is NOT: whether the user really
 * presented a fingerprint before the local secret was handed over is something no server can see
 * (`tool_api.DeviceProofs`, `UserVerification`). This tool takes on that exception knowingly and
 * under the same reservation `auth_device` already carries, rather than opening a second,
 * stricter rule for the very same kind of access means (ADR-21, docs/04-orchestrierung.md #8).
 *
 * `startStep` is `unlock`, not the role default `auth`: the first thing the client does is unlock
 * locally so the PIN can be released - the proof comes a step later.
 */
@Component
object AuthKobilDescriptor : ToolDescriptor {
    override val toolId = ToolId("auth-kobil")
    override val role = MethodRole.IDENTIFIED_AUTH
    override val method = KOBIL_METHOD
    override val startStep = "unlock"
    override val factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE, FactorType.INHERENCE)
    override val maxAcr = AcrLevel.LOA2
    override val demoOnly = SIMULATED_KOBIL
    override val allowsMultipleInstances = true
    override val keyBinding = KOBIL_KEY_BINDING
    override val instanceDisclosure = KOBIL_INSTANCE_DISCLOSURE
}

/** Review 2026-09, ADR-36: the level rests on a simulated counterpart, not on anything this instance can check. */
private val SIMULATED_KOBIL = DemoOnly(
    "Die KOBIL-Gegenstelle ist simuliert (kobil_mock); was ein echter KOBIL-Server zusagt, steht noch aus (Fahrplan-Schritt 27)"
)
