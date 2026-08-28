package com.example.dpop.orchestrator.journey.state

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * There is deliberately no `Identifying` here: without a known account, an identification is not
 * a way to log in - it would create or adopt an account, which is not what this intent is for.
 * The state that would permit it does not exist, so no activation check can be forgotten.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@t")
@JsonSubTypes(
    JsonSubTypes.Type(value = LookupLoginState.Start::class, name = "Start"),
    JsonSubTypes.Type(value = LookupLoginState.Credential::class, name = "Credential"),
    JsonSubTypes.Type(value = LookupLoginState.AdditionalFactor::class, name = "AdditionalFactor"),
    JsonSubTypes.Type(value = LookupLoginState.OfferBinding::class, name = "OfferBinding")
)
sealed interface LookupLoginState : JourneyState {

    data object Start : LookupLoginState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(availableTools: Set<String>): Set<String> = emptySet()
        override val active: ToolRef? get() = null
        override val selectionContext: String get() = "auth"
    }

    data class Credential(
        override val offered: List<String>,
        override val declined: Set<String> = emptySet(),
        override val active: ToolRef? = null
    ) : LookupLoginState, OfferingState {
        override fun withActive(active: ToolRef?) = copy(active = active)
        override val selectionContext: String get() = "auth"
    }

    /**
     * One credential is proven but the channel's own acrFloor is not reached yet. Distinct from
     * [Credential] because the offer is a different one: the account is now KNOWN, so the
     * candidates come from `AuthPolicy.candidateTools` (the ordinary device-auth tools) rather
     * than from the lookup-only set that had to resolve an account first.
     *
     * This state exists because the intent used to have no way to represent "proven, but not
     * enough": it went straight from [Credential] to [OfferBinding], and the channel reached
     * AUTHENTICATED under its own required level.
     */
    data class AdditionalFactor(
        override val offered: List<String>,
        override val declined: Set<String> = emptySet(),
        override val active: ToolRef? = null
    ) : LookupLoginState, OfferingState {
        override fun withActive(active: ToolRef?) = copy(active = active)
        override val selectionContext: String get() = "auth"
    }

    /**
     * Explicit and optional: "recognize this device for future logins?". The device link is a
     * durable device -> account assignment and must not arise as a side effect of a login the
     * user chose precisely because they wanted no device binding.
     */
    data class OfferBinding(val accountId: Long) : LookupLoginState, AnswerableState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(availableTools: Set<String>): Set<String> = emptySet()
        override val active: ToolRef? get() = null
        override val selectionContext: String get() = "authentication"
        override val selectionStep: String get() = "offerDeviceBinding"
    }
}
