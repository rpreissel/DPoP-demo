package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.account.AccountProfile
import com.example.dpop.account.AuthMethodView
import com.example.dpop.auth_device.AuthDeviceDescriptor
import com.example.dpop.auth_device.EnrollDeviceDescriptor
import com.example.dpop.auth_email.AuthEmailLookupDescriptor
import com.example.dpop.auth_email.AuthEmailUseDescriptor
import com.example.dpop.auth_email.EnrollEmailDescriptor
import com.example.dpop.auth_password.AuthPasswordLookupDescriptor
import com.example.dpop.auth_password.AuthPasswordUseDescriptor
import com.example.dpop.auth_password.EnrollPasswordDescriptor
import com.example.dpop.auth_sms.AuthSmsLookupDescriptor
import com.example.dpop.auth_sms.AuthSmsUseDescriptor
import com.example.dpop.auth_sms.EnrollSmsDescriptor
import com.example.dpop.id_eid.IdentEidDescriptor
import com.example.dpop.id_fsc.IdentFscDescriptor
import com.example.dpop.orchestrator.journey.JourneyContext
import com.example.dpop.orchestrator.policy.AuthEvidence
import com.example.dpop.orchestrator.policy.DefaultAuthPolicy
import com.example.dpop.orchestrator.tool.ToolHandlerRegistry
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
            IdentFscDescriptor, IdentEidDescriptor,
            EnrollSmsDescriptor, AuthSmsUseDescriptor, AuthSmsLookupDescriptor,
            EnrollEmailDescriptor, AuthEmailUseDescriptor, AuthEmailLookupDescriptor,
            EnrollPasswordDescriptor, AuthPasswordUseDescriptor, AuthPasswordLookupDescriptor,
            EnrollDeviceDescriptor, AuthDeviceDescriptor
        )
    )
    val policy = DefaultAuthPolicy(catalog)
    val allToolIds: Set<String> = catalog.descriptors().map { it.toolId }.toSet()

    const val BINDING_KEY = "test-binding-key"

    fun account(
        vararg methods: AuthMethodView,
        accountId: Long = 1L,
        emailConfirmed: Boolean = true
    ) = AccountProfile(
        accountId = accountId,
        personId = 1L,
        identifications = emptyList(),
        authenticationMethods = methods.toList(),
        emailConfirmedAt = if (emailConfirmed) Instant.now() else null
    )

    fun method(
        method: String,
        enrolledUnderAcr: String,
        active: Boolean = true,
        details: Map<String, Any?>? = null
    ) = AuthMethodView(
        id = "$method-instance", method = method, active = active,
        createdAt = null, enrolledUnderAcr = enrolledUnderAcr, details = details
    )

    /** A device credential's `details` map, matching what `CandidateTools.preferredDeviceAuth` looks for. */
    fun deviceDetails(bindingKeyRef: String = BINDING_KEY): Map<String, Any?> = mapOf("deviceBindingKeyRef" to bindingKeyRef)

    fun ctx(
        account: AccountProfile? = null,
        evidence: AuthEvidence = AuthEvidence(emptyList(), emptySet()),
        acrFloor: String = "loa1",
        bindingKeyRef: String = BINDING_KEY,
        linkedAccountId: Long? = null,
        isSubJourney: Boolean = false,
        availableTools: Set<String> = allToolIds
    ) = JourneyContext(account, evidence, acrFloor, bindingKeyRef, linkedAccountId, isSubJourney, policy, catalog, availableTools)
}
