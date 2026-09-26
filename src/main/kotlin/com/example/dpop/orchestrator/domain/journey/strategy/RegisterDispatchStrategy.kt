package com.example.dpop.orchestrator.domain.journey.strategy

import com.example.dpop.orchestrator.domain.AuthIntent
import com.example.dpop.orchestrator.domain.FeatureFlags
import com.example.dpop.orchestrator.domain.journey.IntentStrategy
import com.example.dpop.orchestrator.domain.journey.JourneyContext
import com.example.dpop.orchestrator.domain.journey.JourneyEvent
import com.example.dpop.orchestrator.domain.journey.Transition
import com.example.dpop.orchestrator.domain.journey.state.JourneyState
import com.example.dpop.orchestrator.domain.journey.state.RegisterEnrollFirstState
import com.example.dpop.orchestrator.domain.journey.state.RegisterState

/**
 * The single [IntentStrategy] Spring actually registers for [AuthIntent.REGISTER] - Spring only
 * allows one bean per intent (`JourneyService.strategiesByIntent`), so the two REGISTER variants
 * ([RegisterStrategy], the ident-first status quo, and [RegisterEnrollFirstStrategy], the
 * "Enrollment zuerst" experiment) are plain, non-`@Component` classes this dispatcher holds and
 * delegates to - never Spring beans in their own right, so they can never accidentally end up in
 * `List<IntentStrategy<*>>` themselves.
 *
 * Generic over [JourneyState], not [RegisterState]: the two variants have fully independent,
 * non-overlapping state hierarchies ([RegisterState] vs. [RegisterEnrollFirstState] - see the
 * latter's own doc for why they share nothing new). `JourneyService` already stores every strategy
 * type-erased (`Map<AuthIntent, IntentStrategy<*>>`), so this fits the existing machinery without
 * any change there.
 *
 * Deliberately takes no `FeatureFlagService` itself: `IntentStrategy` implementations must
 * never depend on a `@Service`/`@Repository` (its own class doc, enforced by
 * `OrchestratorArchitectureTest` - "a strategy DECIDES, it never ACTS"). `JourneyService` reads the
 * flags once, into [JourneyContext.featureFlags] (`FeatureFlags.REGISTER_ENROLL_FIRST`), the same
 * read-only channel every other decision here already goes through.
 */
class RegisterDispatchStrategy : IntentStrategy<JourneyState> {

    private val identFirst = RegisterStrategy()
    private val enrollFirst = RegisterEnrollFirstStrategy()

    override val intent: AuthIntent = AuthIntent.REGISTER

    /** Read ONCE, only for a brand-new journey - which variant an ALREADY RUNNING journey belongs to is decided by [state]'s own type in [transition], never re-read from the flag, so a flag flip mid-journey can't corrupt it. */
    override fun initialState(ctx: JourneyContext): JourneyState =
        if (FeatureFlags.REGISTER_ENROLL_FIRST in ctx.featureFlags) enrollFirst.initialState(ctx) else identFirst.initialState(ctx)

    override fun transition(state: JourneyState, event: JourneyEvent, ctx: JourneyContext): Transition = when (state) {
        is RegisterEnrollFirstState -> enrollFirst.transition(state, event, ctx)
        is RegisterState -> identFirst.transition(state, event, ctx)
        else -> error("RegisterDispatchStrategy received a foreign state: ${state::class}")
    }

}
