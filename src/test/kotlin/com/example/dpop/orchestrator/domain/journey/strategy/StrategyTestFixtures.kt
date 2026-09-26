package com.example.dpop.orchestrator.domain.journey.strategy

import com.example.dpop.orchestrator.domain.ChannelType
import com.example.dpop.account.AccountProfile
import com.example.dpop.account.AuthMethodView
import com.example.dpop.auth_device.AuthDeviceDescriptor
import com.example.dpop.auth_device.EnrollDeviceDescriptor
import com.example.dpop.auth_kobil.AuthKobilDescriptor
import com.example.dpop.auth_kobil.EnrollKobilDescriptor
import com.example.dpop.auth_email.AuthEmailLookupDescriptor
import com.example.dpop.auth_email.AuthEmailDescriptor
import com.example.dpop.auth_email.ConfirmEmailDescriptor
import com.example.dpop.auth_email.EnrollEmailDescriptor
import com.example.dpop.auth_password.AuthPasswordLookupDescriptor
import com.example.dpop.auth_password.AuthPasswordDescriptor
import com.example.dpop.auth_password.EnrollPasswordDescriptor
import com.example.dpop.auth_qr.AuthQrDescriptor
import com.example.dpop.auth_qr.AuthQrLookupDescriptor
import com.example.dpop.auth_qr.ConfirmQrLoginDescriptor
import com.example.dpop.auth_qr.EnrollQrDescriptor
import com.example.dpop.auth_sms.AuthSmsLookupDescriptor
import com.example.dpop.auth_sms.AuthSmsDescriptor
import com.example.dpop.auth_sms.EnrollSmsDescriptor
import com.example.dpop.id_eid.IdentEidDescriptor
import com.example.dpop.id_nect.IdentNectDescriptor
import com.example.dpop.id_fsc.IdentFscDescriptor
import com.example.dpop.id_kvnr.IdentKvnrDescriptor
import com.example.dpop.orchestrator.domain.journey.JourneyContext
import com.example.dpop.orchestrator.domain.policy.AuthEvidence
import com.example.dpop.orchestrator.domain.policy.DefaultAuthPolicy
import com.example.dpop.orchestrator.tool.ToolHandlerRegistry
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.ToolId
import com.example.dpop.tool_spi.TrustLevel
import java.time.Instant

/**
 * Shared fixtures for [IntentStrategy] unit tests - built on the REAL catalog (every module's own
 * `Descriptors.kt` object), not a synthetic stand-in, so strategy decisions are exercised against
 * the exact candidate resolution production uses (docs/03-tool-architektur.md #1: the aggregation
 * of all descriptors IS the catalog). Every descriptor object is a plain Kotlin `object`, so none
 * of this needs a Spring context.
 */
object StrategyTestFixtures {

    val catalog = ToolHandlerRegistry(
        listOf(
            IdentFscDescriptor, IdentEidDescriptor, IdentNectDescriptor, IdentKvnrDescriptor,
            EnrollSmsDescriptor, AuthSmsDescriptor, AuthSmsLookupDescriptor,
            ConfirmEmailDescriptor, EnrollEmailDescriptor, AuthEmailDescriptor, AuthEmailLookupDescriptor,
            EnrollPasswordDescriptor, AuthPasswordDescriptor, AuthPasswordLookupDescriptor,
            EnrollDeviceDescriptor, AuthDeviceDescriptor,
            EnrollKobilDescriptor, AuthKobilDescriptor,
            EnrollQrDescriptor, AuthQrDescriptor, AuthQrLookupDescriptor, ConfirmQrLoginDescriptor
        )
    )
    val policy = DefaultAuthPolicy(catalog)
    val allToolIds: Set<ToolId> = catalog.descriptors().map { it.toolId }.toSet()

    const val BINDING_KEY = "test-binding-key"

    /**
     * [attestedIdentity] mirrors what an attestation (`ident-eid`) leaves on an account - the
     * claims `ident-kvnr`'s own `requires` is checked against. Off by default, so the ordinary
     * fixture keeps offering exactly the tools it always did.
     */
    fun account(
        vararg methods: AuthMethodView,
        accountId: Long = 1L,
        personId: String? = "P000000001",
        emailConfirmed: Boolean = true,
        attestedIdentity: Boolean = false
    ) = AccountProfile(
        accountId = accountId,
        personId = personId,
        authenticationMethods = methods.toList(),
        emailConfirmedAt = if (emailConfirmed) Instant.now() else null,
        establishedClaims = buildMap {
            // A confirmed address IS an EMAIL claim at PROVEN - the anchor is only its projection.
            if (emailConfirmed) put(AttributeType.EMAIL, TrustLevel.PROVEN)
            if (attestedIdentity) {
                put(AttributeType.FAMILY_NAME, TrustLevel.PROVEN)
                put(AttributeType.GIVEN_NAMES, TrustLevel.PROVEN)
                put(AttributeType.BIRTH_DATE, TrustLevel.PROVEN)
            }
        }
    )

    fun method(
        method: String,
        enrolledUnderAcr: AcrLevel,
        active: Boolean = true,
        details: Map<String, Any?>? = null
    ) = AuthMethodView(
        id = "$method-instance", method = method, active = active,
        createdAt = null, enrolledUnderAcr = enrolledUnderAcr.value, details = details,
        enrollmentRef = EnrollmentRef("${method}_enrollment", "1")
    )

    /** A device credential's `details` map, matching what `CandidateTools.preferredDeviceAuth` looks for. */
    fun deviceDetails(bindingKeyRef: String = BINDING_KEY): Map<String, Any?> = mapOf("deviceBindingKeyRef" to bindingKeyRef)

    /**
     * Test convenience: derives each method's own loa from the REAL catalog (its highest maxAcr
     * among matching descriptors) - what a real caller (JourneyService) would resolve before
     * calling into AuthPolicy, now that pricing no longer looks the catalog up itself.
     * [enrolledUnderAcr], when given an account, is that account's own enrollment record for each
     * method in [amr] - only needed for scenarios that actually exercise the MFA-combination bump.
     */
    /**
     * [amrSourceId] names the tool behind an amr value where the two differ - ident-nect reports
     * `nect-<procedure>`, not its method name.
     */
    fun evidence(
        amr: List<String>,
        factorTypes: Set<FactorType>,
        account: AccountProfile? = null,
        amrSourceId: Map<String, String> = emptyMap()
    ): AuthEvidence {
        val methodAcr = amr.associateWith { m ->
            catalog.descriptors().filter { it.method == m }.maxByOrNull { AcrLevel.rank(it.maxAcr) }?.maxAcr?.value ?: AcrLevel.NONE.value
        }
        val enrolledUnderAcr = account?.authenticationMethods
            ?.filter { it.method in amr }
            ?.mapNotNull { m -> m.enrolledUnderAcr?.let { m.method to it } }
            ?.toMap()
            ?: emptyMap()
        return AuthEvidence.from(amr, factorTypes, methodAcr, enrolledUnderAcr, amrSourceId = amrSourceId)
    }

    fun ctx(
        account: AccountProfile? = null,
        evidence: AuthEvidence = AuthEvidence(emptyList()),
        acrFloor: AcrLevel = AcrLevel.LOA1,
        bindingKeyRef: String = BINDING_KEY,
        // Defaults to "this device is already linked to the context's own account" - the ordinary
        // single-device scenario nearly every test wants; a test exercising a genuine device-rebind
        // conflict (docs/09-dpop.md) passes an explicit, different accountId here instead.
        linkedAccountId: Long? = account?.accountId,
        isSubJourney: Boolean = false,
        availableTools: Set<ToolId> = allToolIds,
        channel: ChannelType = ChannelType.APP
    ) = JourneyContext(channel, account, evidence, acrFloor, bindingKeyRef, linkedAccountId, isSubJourney, policy, catalog, availableTools)
}
