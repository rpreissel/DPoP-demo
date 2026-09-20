package com.example.dpop.orchestrator.journey.state

import com.example.dpop.tool_spi.ToolId
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * The "Enrollment zuerst" experiment (docs/04-orchestrierung.md, REGISTER): an alternative
 * REGISTER journey, toggled at runtime via `FeatureFlagService`, that enrolls a login method
 * (and, on KEYCLOAK, a password) BEFORE any identification - completely autark from
 * [RegisterState]/`AuthEnrollCore` (its own states, its own transition function, no shared types
 * beyond ones that already existed before this experiment, like `RE_IDENTIFY`): the two journeys
 * answer genuinely different questions ("who is this, then what can they use to log in?" vs. "what
 * can they use to log in, and do we even know who they are?") and must stay independently readable.
 *
 * Every state name is prefixed `EnrollFirst*`, even though [JourneyStateCodec]'s own `@JsonTypeInfo`
 * discriminator ("@t") would already disambiguate it from [RegisterState] without that - `AuthJourney
 * .stateType` (`javaClass.simpleName`, a SEPARATE plain-text column used for observability queries
 * like "how many journeys are stuck in Enrolling?") is not namespaced by sealed root, so a bare
 * `Enrolling` here would silently collide with [RegisterState.Enrolling] in that column.
 * [JourneyStateCodec.read] additionally needs its own REGISTER-specific fallback to try both sealed
 * roots, since one intent normally maps to exactly one - see its own doc.
 *
 * Identification is NOT a pflicht here and can stay absent indefinitely - the account this journey
 * produces is created with `personId = null` (`AccountService.createUnidentifiedAccount`, lazily,
 * on the first completed enrollment) and only ever bound to a person later, optionally, through
 * the pre-existing `RE_IDENTIFY` sub-journey (`ReIdentifyState.forSubJourney`) - both from the
 * explicit offer at the end of this chain ([EnrollFirstStart]'s own `SubJourneyFinished`/
 * `SubJourneyCancelled` handling) and from any later step-up that needs a higher IAL. Its own
 * `OfferReIdent` prompt ("Erneut identifizieren?") already asks exactly the accept/decline question
 * this experiment needs - no separate confirmation state is added here for it.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@t")
@JsonSubTypes(
    JsonSubTypes.Type(value = RegisterEnrollFirstState.EnrollFirstStart::class, name = "EnrollFirstStart"),
    JsonSubTypes.Type(value = RegisterEnrollFirstState.EnrollFirstAttestingEmail::class, name = "EnrollFirstAttestingEmail"),
    JsonSubTypes.Type(value = RegisterEnrollFirstState.EnrollFirstEnrollingSms::class, name = "EnrollFirstEnrollingSms"),
    JsonSubTypes.Type(value = RegisterEnrollFirstState.EnrollFirstEnrolling::class, name = "EnrollFirstEnrolling"),
    JsonSubTypes.Type(value = RegisterEnrollFirstState.EnrollFirstConfirmingEmail::class, name = "EnrollFirstConfirmingEmail"),
    JsonSubTypes.Type(value = RegisterEnrollFirstState.EnrollFirstPasswordObligation::class, name = "EnrollFirstPasswordObligation"),
    JsonSubTypes.Type(value = RegisterEnrollFirstState.EnrollFirstConfirmDeviceRebind::class, name = "EnrollFirstConfirmDeviceRebind")
)
sealed interface RegisterEnrollFirstState : JourneyState {

    /**
     * Both the journey's true beginning (no account yet - one is created lazily on the first
     * completed enrollment, see class doc) AND the resume marker for the closing, optional
     * RE_IDENTIFY sub-journey (same double role [RegisterState.Start] already plays for the
     * ident-first journey) - distinguished purely by which [com.example.dpop.orchestrator.journey.
     * JourneyEvent] arrives, never rendered with an offer of its own either way.
     */
    data object EnrollFirstStart : RegisterEnrollFirstState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(availableTools: Set<ToolId>): Set<ToolId> = emptySet()
        override val active: ToolRef? get() = null
        override val selectionContext: String get() = "enrollment"
    }

    /**
     * This device is durably linked to a DIFFERENT account, so the implicit binding every other
     * REGISTER run gets was deliberately skipped: `JourneyActionExecutor.linkDeviceIfIntentImplies`
     * never rebinds on its own, because succeeding at a flow is consent to be recognized by THIS
     * account - never consent to take the device away from another one, which also revokes that
     * account's device credentials.
     *
     * So this journey asks, at the very END rather than where the binding would have happened:
     * by then the optional identification has already run (or been declined), which is exactly the
     * information the question needs - at the first enrollment the account had only just been
     * created lazily and had no identity to name at all. Same shape and same destructive wording
     * as [LookupLoginState.ConfirmDeviceRebind]; declining simply finishes the registration
     * without a device link (logging in still works through the lookup tools).
     */
    data object EnrollFirstConfirmDeviceRebind : RegisterEnrollFirstState, AnswerableState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(availableTools: Set<ToolId>): Set<ToolId> = emptySet()
        override val active: ToolRef? get() = null
        override val prompt: Prompt get() = Prompt.Confirm(
            title = "Dieses Gerät ist bereits einem anderen Konto zugeordnet",
            description = "Wenn Sie fortfahren, wird dieses Gerät künftig nur noch Ihrem neuen Konto " +
                "zugeordnet. Das bisher verbundene Konto muss sich beim nächsten Mal auf diesem " +
                "Gerät erneut identifizieren. Ohne Zuordnung bleibt Ihr neues Konto nutzbar - Sie " +
                "melden sich dann künftig über E-Mail und Passwort an.",
            confirmLabel = "Gerät neu zuordnen",
            cancelLabel = "Ohne Zuordnung fortfahren",
            destructive = true
        )
    }

    /**
     * Forced, mandatory first step - only tools that ATTEST an email address are offered, and
     * declining (`Abandoned`) simply re-offers the same set (see `reoffer`): there is no skipping
     * ahead to [EnrollFirstEnrollingSms] short of actually confirming an address. Only bypassed if
     * no such tool is available at all right now (admin-disabled) - see
     * `RegisterEnrollFirstStrategy.offerEmailConfirmation`. Same first-step ordering the ident-first
     * journey now uses as well (`AuthEnrollCore.confirmEmail`).
     */
    data class EnrollFirstAttestingEmail(
        override val offered: List<ToolId>,
        override val declined: Set<ToolId> = emptySet(),
        override val active: ToolRef? = null
    ) : RegisterEnrollFirstState, OfferingState {
        override fun withActive(active: ToolRef?) = copy(active = active)
        override val selectionContext: String get() = "enrollment"
        override val selectionTitle: String get() = "E-Mail-Adresse bestätigen"
        override val selectionDescription: String get() = "Zuerst wird Ihre E-Mail-Adresse bestätigt - Ihr Konto wird darüber gefunden. SMS folgt danach, die Identifikation ist optional und kommt erst zum Schluss."
    }

    /** Forced, mandatory second step, reached only once [EnrollFirstAttestingEmail] is discharged (or skipped, see there) - same non-skippable reasoning. */
    data class EnrollFirstEnrollingSms(
        override val offered: List<ToolId>,
        override val declined: Set<ToolId> = emptySet(),
        override val active: ToolRef? = null
    ) : RegisterEnrollFirstState, OfferingState {
        override fun withActive(active: ToolRef?) = copy(active = active)
        override val selectionContext: String get() = "enrollment"
        override val selectionTitle: String get() = "SMS als Anmeldeverfahren einrichten"
        override val selectionDescription: String get() = "Danach wird SMS als zweites Anmeldeverfahren eingerichtet - die Identifikation ist optional und kommt erst zum Schluss."
    }

    /**
     * Fallback for whatever the mandatory email/SMS steps above didn't already cover: reached only
     * when the ACR floor still isn't reachable after both (e.g. it requires a method neither email
     * nor SMS can provide), or when NEITHER was available at all when the journey started (in which
     * case this is where it started from instead, see `RegisterEnrollFirstStrategy.offerEnrollment`).
     */
    data class EnrollFirstEnrolling(
        override val offered: List<ToolId>,
        override val declined: Set<ToolId> = emptySet(),
        override val active: ToolRef? = null
    ) : RegisterEnrollFirstState, OfferingState {
        override fun withActive(active: ToolRef?) = copy(active = active)
        override val selectionContext: String get() = "enrollment"
        override val selectionTitle: String get() = "Anmeldeverfahren einrichten"
        override val selectionDescription: String get() = "Richten Sie ein Anmeldeverfahren ein - die Identifikation ist optional und folgt erst danach."
    }

    data class EnrollFirstConfirmingEmail(
        override val offered: List<ToolId>,
        override val declined: Set<ToolId> = emptySet(),
        override val active: ToolRef? = null
    ) : RegisterEnrollFirstState, OfferingState {
        override fun withActive(active: ToolRef?) = copy(active = active)
        override val selectionContext: String get() = "enrollment"
        override val selectionTitle: String get() = "E-Mail-Bestätigung ausstehend"
        override val selectionDescription: String get() = "Ihre E-Mail-Adresse muss noch bestätigt werden - Ihr Konto wird darüber gefunden."
    }

    /** Same reasoning as [RegisterState.PasswordObligation] - just reached before, not after, any identification. */
    data class EnrollFirstPasswordObligation(
        override val offered: List<ToolId>,
        override val declined: Set<ToolId> = emptySet(),
        override val active: ToolRef? = null
    ) : RegisterEnrollFirstState, OfferingState {
        override fun withActive(active: ToolRef?) = copy(active = active)
        override val selectionContext: String get() = "enrollment"
        override val selectionTitle: String get() = "Passwort einrichten"
        override val selectionDescription: String get() = "Für die Registrierung ist ein Passwort als Anmeldeverfahren erforderlich."
    }
}
