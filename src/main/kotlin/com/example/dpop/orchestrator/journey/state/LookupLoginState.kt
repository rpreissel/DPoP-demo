package com.example.dpop.orchestrator.journey.state

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * There is deliberately no `Identifying` that could ADOPT an account here: without a known
 * account, an identification is not a way to log in - it would create or adopt one, which is not
 * what this intent is for. [ReIdentifying] is different: it only ever re-CONFIRMS the account
 * already resolved by the initial lookup, offered only once that account is known - and only
 * after [OfferReIdent] asks first, never as a silent fallback.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@t")
@JsonSubTypes(
    JsonSubTypes.Type(value = LookupLoginState.Start::class, name = "Start"),
    JsonSubTypes.Type(value = LookupLoginState.Credential::class, name = "Credential"),
    JsonSubTypes.Type(value = LookupLoginState.AdditionalFactor::class, name = "AdditionalFactor"),
    JsonSubTypes.Type(value = LookupLoginState.OfferReIdent::class, name = "OfferReIdent"),
    JsonSubTypes.Type(value = LookupLoginState.ReIdentifying::class, name = "ReIdentifying"),
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
        override val selectionTitle: String get() = "Wie möchten Sie sich anmelden?"
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
        override val selectionTitle: String get() = "Zusätzlichen Faktor bestätigen"
    }

    /**
     * "No active method reaches the target - re-identify instead?" A plain yes/no, asked before
     * ever falling through to [ReIdentifying] - re-identification is a heavier action than
     * picking another factor, so it's never a silent fallback.
     */
    data object OfferReIdent : LookupLoginState, AnswerableState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(availableTools: Set<String>): Set<String> = emptySet()
        override val active: ToolRef? get() = null
        override val prompt: Prompt
            get() = Prompt.Confirm(
                title = "Erneut identifizieren?",
                description = "Mit den vorhandenen Anmeldeverfahren ist das geforderte Sicherheitsniveau " +
                    "nicht erreichbar. Sie können sich stattdessen erneut identifizieren, um es direkt zu erreichen.",
                confirmLabel = "Erneut identifizieren",
                cancelLabel = "Abbrechen"
            )
    }

    /**
     * The way out once no active method can close the gap: `ident-fsc` reaches loa2 on its own.
     * Unlike [Credential] this only ever CONFIRMS the account already resolved, never adopts a
     * different one (`LookupLoginStrategy.interpret`'s `ConfirmIdentity`, not `AdoptIdentity`) -
     * a session that merely proved loa1 must not be able to smuggle in someone else's identity.
     */
    data class ReIdentifying(
        override val offered: List<String>,
        override val declined: Set<String> = emptySet(),
        override val active: ToolRef? = null
    ) : LookupLoginState, OfferingState {
        override fun withActive(active: ToolRef?) = copy(active = active)
        override val selectionContext: String get() = "auth"
        override val selectionTitle: String get() = "Identifizieren Sie sich erneut"
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
        override val prompt: Prompt get() = Prompt.Confirm(
            title = "Dieses Gerät merken?",
            description = "Wenn Sie zustimmen, erkennt der Dienst dieses Gerät beim nächsten Mal " +
                "wieder und Sie müssen Ihre E-Mail-Adresse nicht erneut eingeben. Sie können auch " +
                "ohne Bindung fortfahren – dann melden Sie sich künftig wieder über E-Mail und " +
                "Passwort an.",
            confirmLabel = "Gerät merken",
            cancelLabel = "Ohne Bindung fortfahren"
        )
    }
}
