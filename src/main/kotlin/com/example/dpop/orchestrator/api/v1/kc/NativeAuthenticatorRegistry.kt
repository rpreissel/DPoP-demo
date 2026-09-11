package com.example.dpop.orchestrator.api.v1.kc

import com.example.dpop.tool_spi.FactorType
import org.springframework.stereotype.Component

/**
 * Stable, per-authenticator-TYPE metadata for a native Keycloak authenticator (docs/05-api.md
 * Abschnitt 3) - the kc-facade's own mirror of `ToolDescriptor` on the
 * orchestrator side, field for field: [method] and [maxAcr] are exactly as fixed per authenticator
 * TYPE as `ToolDescriptor.method`/`maxAcr` are per tool (many orchestrator tool handlers already
 * report `achievedAcr = descriptor.maxAcr` directly, never a caller-chosen value - the same idiom
 * applies here). Looked up by [nativeToolId] (Keycloak's own stable authenticator/execution-config
 * id - fixed for that authenticator's whole configuration, NOT the per-PROOF `amrSourceId` an
 * individual `AmrEntry` also carries), so a native `AmrEntry` only ever needs to send
 * `nativeToolId`+`amrSourceId`: everything else about the proof - [method], its own [maxAcr], and
 * [factorTypes] - comes from here, the same way an orchestrator tool's evidence is priced from its
 * `ToolDescriptor` rather than resent on every outcome.
 */
data class NativeAuthenticatorDescriptor(
    val nativeToolId: String,
    val method: String,
    val maxAcr: String,
    val factorTypes: Set<FactorType>,
)

/**
 * Demo/mock registry (docs/11-umsetzungsplan.md: no real Keycloak realm to introspect) - a small,
 * fixed set standing in for what a real deployment would configure per Authenticator execution
 * (an admin wiring up `OrchestratorAuthenticator`/native executions in a Keycloak flow). Kc-facade
 * -only, mirrors `ToolHandlerRegistry` in spirit but deliberately NOT the same registry: native
 * authenticators are not orchestrator tools, and the orchestrator's own catalog must stay ignorant
 * of them (docs/05-api.md Abschnitt 3 - a natively reported method's evidence is priced
 * without any catalog lookup at all).
 */
@Component
class NativeAuthenticatorRegistry {
    // Same loa1 ceiling the orchestrator's own equivalent descriptors use (AuthPasswordUseDescriptor,
    // AuthSmsUseDescriptor) - no reason for a native authenticator to claim more than its
    // orchestrator counterpart absent an actual, real difference in what it checks. This is each
    // method's own INDIVIDUAL ceiling only - two DISTINCT native methods (different factorTypes)
    // proven together still combine to loa2 via DefaultAuthPolicy's MFA bump exactly like two
    // orchestrator methods would (KcChannelService deliberately does not cap the combination's
    // own enrolledUnderAcr down to this value - see its own comment for why).
    private val byId: Map<String, NativeAuthenticatorDescriptor> = listOf(
        NativeAuthenticatorDescriptor("kc-password-form", "password", "loa1", setOf(FactorType.KNOWLEDGE)),
        NativeAuthenticatorDescriptor("kc-otp-form", "otp", "loa1", setOf(FactorType.POSSESSION)),
        NativeAuthenticatorDescriptor("kc-sms-form", "sms", "loa1", setOf(FactorType.POSSESSION)),
    ).associateBy { it.nativeToolId }

    fun descriptorFor(nativeToolId: String): NativeAuthenticatorDescriptor? = byId[nativeToolId]
}
