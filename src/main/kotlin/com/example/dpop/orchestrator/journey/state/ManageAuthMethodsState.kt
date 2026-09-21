package com.example.dpop.orchestrator.journey.state

import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.ToolId
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * The only intent without a policy goal: ONE successful enrollment ends it, regardless of the
 * level reached - the channel was already AUTHENTICATED. Adding a second method means a new
 * journey.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@t")
@JsonSubTypes(
    JsonSubTypes.Type(value = ManageAuthMethodsState.AddRequested::class, name = "AddRequested"),
    JsonSubTypes.Type(value = ManageAuthMethodsState.RemoveRequested::class, name = "RemoveRequested"),
    JsonSubTypes.Type(value = ManageAuthMethodsState.Enrolling::class, name = "Enrolling")
)
sealed interface ManageAuthMethodsState : JourneyState {

    /**
     * The user's wish, before the loa2 gate has been evaluated - and the state the journey is
     * parked in while a step-up sub-journey runs. That is exactly why an intention survives the
     * detour: whoever wanted to remove a method and had to prove loa2 first removes it afterwards
     * without acting again. No separate "awaiting" state is needed - JourneyLifecycle.SUSPENDED
     * plus the child's parentJourneyId already say that, and a second copy could only drift.
     */
    data object AddRequested : ManageAuthMethodsState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(availableTools: Set<ToolId>): Set<ToolId> = emptySet()
        override val active: ToolRef? get() = null
        override val selectionContext: String get() = "enrollment"
    }

    data class RemoveRequested(val methodInstanceId: String) : ManageAuthMethodsState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(availableTools: Set<ToolId>): Set<ToolId> = emptySet()
        override val active: ToolRef? get() = null
        override val selectionContext: String get() = "enrollment"
    }

    /**
     * The wish to withdraw an account attribute (a confirmed address), gated exactly like
     * [RemoveRequested] - it is the same kind of destructive self-service act, and it can take
     * credentials with it (`JourneyActionExecutor.performRetractAttribute`).
     */
    data class RetractAttributeRequested(val attributeType: AttributeType) : ManageAuthMethodsState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(availableTools: Set<ToolId>): Set<ToolId> = emptySet()
        override val active: ToolRef? get() = null
        override val selectionContext: String get() = "enrollment"
    }

    data class Enrolling(
        override val offer: Offer
    ) : ManageAuthMethodsState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        override val selectionContext: String get() = "enrollment"
        override val selectionTitle: String get() = "Neues Anmeldeverfahren hinzufügen"
        override val selectionDescription: String get() = "Sie möchten ein weiteres Verfahren einrichten. Wählen Sie aus, welches Sie hinzufügen möchten."
    }
}
