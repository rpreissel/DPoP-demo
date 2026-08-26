package com.example.dpop.orchestrator.journey

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.util.UUID

/**
 * The position on the path, together with the attributes that hold at exactly this position
 * (docs/04-orchestrierung.md #1). A plain status word would say "user is choosing a method"; a
 * [JourneyState] also says WHICH ones were offered and which the user has already declined.
 *
 * Every intent owns its own sealed set - the states of MANAGE make no sense for LOGIN_LOOKUP and
 * are not expressible there. A forgotten position is therefore a compile error in a `when`, not a
 * plausible-looking runtime default.
 *
 * This is also the single source for two questions that otherwise drift apart: "which tool may
 * the client activate now?" and "where do I send them next?". Both are answered by
 * [activatable] - see [JourneyMachine.nextFor].
 */
sealed interface JourneyState {
    /** Empty for states that wait on something other than a tool (e.g. a sub-journey). */
    fun activatable(): Set<String>

    /** The tool that is actually running, once one has been activated. */
    val active: ToolRef?

    /** `next.context` of the orchestrator-owned page this state maps to. */
    val selectionContext: String

    /** `next.step` of that page. */
    val selectionStep: String
        get() = "selectMethod"

    /**
     * The same state with a different running tool. Declared per state rather than derived
     * reflectively: a state that structurally cannot host a tool (a confirmation, a parked wish)
     * says so by ignoring this, and the compiler forces every new state to make that choice.
     */
    fun withActive(active: ToolRef?): JourneyState
}

/**
 * Which ToolSession is authorized to act as [toolId] right now - not just any ToolSession row
 * with a matching toolId. Without the id, an orphaned/superseded ToolSession (e.g. from a
 * duplicate activation request) would keep passing a toolId-only check even though its own
 * tool-module data was never populated, surfacing as a confusing "Unknown tool session" error
 * deep inside the module instead of a clean 409 at the boundary.
 */
data class ToolRef(val toolId: String, val toolSessionId: UUID, val step: String)

/** Shared shape of every state that offers a set of tools and remembers what was declined. */
sealed interface OfferingState : JourneyState {
    val offered: List<String>
    val declined: Set<String>

    override fun activatable(): Set<String> = offered.toSet() - declined

    /**
     * True once every offer here has been declined. Only fallback states ever reach this: a
     * mandatory state re-offers its full set instead of narrowing it, so `declined` never grows
     * there (see FastStrategy.reoffer).
     */
    val exhausted: Boolean
        get() = activatable().isEmpty()
}

// FAST ----------------------------------------------------------------------

/**
 * The fallback chain from the most convenient to the most laborious way in, followed by the mandatory
 * states that make sure the next login works again (docs/04-orchestrierung.md #3).
 *
 * Two deliberately DIFFERENT transition rules live in one hierarchy, and that difference is named
 * here rather than left to a comment:
 * - [PreferredAuth], [AuthChoice], [Identifying] are FALLBACK states: declining moves on.
 * - [ConfirmingEmail], [Enrolling] are MANDATORY: only fulfilling moves on.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@t")
@JsonSubTypes(
    JsonSubTypes.Type(value = FastState.Start::class, name = "Start"),
    JsonSubTypes.Type(value = FastState.PreferredAuth::class, name = "PreferredAuth"),
    JsonSubTypes.Type(value = FastState.AuthChoice::class, name = "AuthChoice"),
    JsonSubTypes.Type(value = FastState.Identifying::class, name = "Identifying"),
    JsonSubTypes.Type(value = FastState.ConfirmingEmail::class, name = "ConfirmingEmail"),
    JsonSubTypes.Type(value = FastState.Enrolling::class, name = "Enrolling")
)
sealed interface FastState : JourneyState {

    data object Start : FastState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(): Set<String> = emptySet()
        override val active: ToolRef? get() = null
        override val selectionContext: String get() = "auth"
    }

    /** Linked device with a matching device method: exactly one default suggestion. */
    data class PreferredAuth(val toolId: String, override val active: ToolRef? = null) : FastState {
        override fun withActive(active: ToolRef?) = copy(active = active)
        override fun activatable(): Set<String> = setOf(toolId)
        override val selectionContext: String get() = "auth"
    }

    /** Other authentication methods the account already has. */
    data class AuthChoice(
        override val offered: List<String>,
        override val declined: Set<String> = emptySet(),
        override val active: ToolRef? = null
    ) : FastState, OfferingState {
        override fun withActive(active: ToolRef?) = copy(active = active)
        override val selectionContext: String get() = "auth"
    }

    /**
     * Last fallback state: identification - here for login AND registration alike. Which one it
     * was is decided afterwards by `findOrCreateAccount`, which is exactly why a single state
     * covers both.
     */
    data class Identifying(
        override val offered: List<String>,
        override val declined: Set<String> = emptySet(),
        override val active: ToolRef? = null
    ) : FastState, OfferingState {
        override fun withActive(active: ToolRef?) = copy(active = active)
        override val selectionContext: String get() = "registration"
        override val selectionStep: String get() = "selectIdentificationMethod"
    }

    data class ConfirmingEmail(
        override val offered: List<String>,
        override val declined: Set<String> = emptySet(),
        override val active: ToolRef? = null
    ) : FastState, OfferingState {
        override fun withActive(active: ToolRef?) = copy(active = active)
        override val selectionContext: String get() = "enrollment"
    }

    /**
     * [emailObligation] records that this run passed through [Identifying], i.e. it created or
     * adopted an account. Only then does the confirmed-email state apply afterwards: a chosen
     * password needs an identifier to hang off of, so such a run must not finish leaving
     * `enroll-password` permanently unreachable. A plain login is never blocked on it.
     *
     * It is an attribute of THIS state rather than a second one in front of it, because the
     * obligation is checked when enrolment is done - putting it first would take away
     * the choice of which method to set up.
     */
    data class Enrolling(
        override val offered: List<String>,
        override val declined: Set<String> = emptySet(),
        override val active: ToolRef? = null,
        val emailObligation: Boolean = false
    ) : FastState, OfferingState {
        override fun withActive(active: ToolRef?) = copy(active = active)
        override val selectionContext: String get() = "enrollment"
    }
}

// LOGIN_LOOKUP ---------------------------------------------------------------

/**
 * There is deliberately no `Identifying` here: without a known account, an identification is not
 * a way to log in - it would create or adopt an account, which is not what this intent is for.
 * The state that would permit it does not exist, so no activation check can be forgotten.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@t")
@JsonSubTypes(
    JsonSubTypes.Type(value = LookupState.Start::class, name = "Start"),
    JsonSubTypes.Type(value = LookupState.Credential::class, name = "Credential"),
    JsonSubTypes.Type(value = LookupState.OfferBinding::class, name = "OfferBinding")
)
sealed interface LookupState : JourneyState {

    data object Start : LookupState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(): Set<String> = emptySet()
        override val active: ToolRef? get() = null
        override val selectionContext: String get() = "auth"
    }

    data class Credential(
        override val offered: List<String>,
        override val declined: Set<String> = emptySet(),
        override val active: ToolRef? = null
    ) : LookupState, OfferingState {
        override fun withActive(active: ToolRef?) = copy(active = active)
        override val selectionContext: String get() = "auth"
    }

    /**
     * Explicit and optional: "recognize this device for future logins?". The device link is a
     * durable device -> account assignment and must not arise as a side effect of a login the
     * user chose precisely because they wanted no device binding.
     */
    data class OfferBinding(val accountId: Long) : LookupState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(): Set<String> = emptySet()
        override val active: ToolRef? get() = null
        override val selectionContext: String get() = "authentication"
        override val selectionStep: String get() = "offerDeviceBinding"
    }
}

// STEP_UP --------------------------------------------------------------------

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@t")
@JsonSubTypes(
    JsonSubTypes.Type(value = StepUpState.Start::class, name = "Start"),
    JsonSubTypes.Type(value = StepUpState.AuthChoice::class, name = "AuthChoice"),
    JsonSubTypes.Type(value = StepUpState.ReIdentifying::class, name = "ReIdentifying")
)
sealed interface StepUpState : JourneyState {
    /** The goal of THIS run - distinct from the channel's durable `acrFloor`. */
    val targetAcr: String

    data class Start(override val targetAcr: String, val startingAcr: String) : StepUpState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(): Set<String> = emptySet()
        override val active: ToolRef? get() = null
        override val selectionContext: String get() = "auth"
    }

    data class AuthChoice(
        override val targetAcr: String,
        val startingAcr: String,
        override val offered: List<String>,
        override val declined: Set<String> = emptySet(),
        override val active: ToolRef? = null
    ) : StepUpState, OfferingState {
        override fun withActive(active: ToolRef?) = copy(active = active)
        override val selectionContext: String get() = "auth"
    }

    /**
     * The way out of the ONE-METHOD dead end: an account with exactly one active auth method has
     * nothing to combine with, so after a fresh device-bound login (loa1 only) it could never
     * reach loa2. `ident-fsc` reaches loa2 on its own. Deliberately its own state rather than part
     * of [AuthChoice] - re-identification must never look like a generic login shortcut, only like
     * the exit from this one dead end.
     */
    data class ReIdentifying(
        override val targetAcr: String,
        val startingAcr: String,
        override val offered: List<String>,
        override val declined: Set<String> = emptySet(),
        override val active: ToolRef? = null
    ) : StepUpState, OfferingState {
        override fun withActive(active: ToolRef?) = copy(active = active)
        override val selectionContext: String get() = "auth"
    }
}

// MANAGE ---------------------------------------------------------------------

/**
 * The only intent without a policy goal: ONE successful enrollment ends it, regardless of the
 * level reached - the channel was already AUTHENTICATED. Adding a second method means a new
 * journey.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@t")
@JsonSubTypes(
    JsonSubTypes.Type(value = ManageState.AddRequested::class, name = "AddRequested"),
    JsonSubTypes.Type(value = ManageState.RemoveRequested::class, name = "RemoveRequested"),
    JsonSubTypes.Type(value = ManageState.Enrolling::class, name = "Enrolling")
)
sealed interface ManageState : JourneyState {

    /**
     * The user's wish, before the loa2 gate has been evaluated - and the state the journey is
     * parked in while a step-up sub-journey runs. That is exactly why an intention survives the
     * detour: whoever wanted to remove a method and had to prove loa2 first removes it afterwards
     * without acting again. No separate "awaiting" state is needed - JourneyLifecycle.SUSPENDED
     * plus the child's parentJourneyId already say that, and a second copy could only drift.
     */
    data object AddRequested : ManageState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(): Set<String> = emptySet()
        override val active: ToolRef? get() = null
        override val selectionContext: String get() = "enrollment"
    }

    data class RemoveRequested(val methodInstanceId: String) : ManageState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(): Set<String> = emptySet()
        override val active: ToolRef? get() = null
        override val selectionContext: String get() = "enrollment"
    }

    data class Enrolling(
        override val offered: List<String>,
        override val declined: Set<String> = emptySet(),
        override val active: ToolRef? = null
    ) : ManageState, OfferingState {
        override fun withActive(active: ToolRef?) = copy(active = active)
        override val selectionContext: String get() = "enrollment"
    }
}
