package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.Decision
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.state.FastAccessState
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.account
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.ctx
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.deviceDetails
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.method
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Pure unit coverage of [RegisterStrategy] - deliberately fresh identification even on an already
 * linked device (docs/04-orchestrierung.md, "REGISTER"). Everything from identification onward is
 * inherited from [FastAccessStrategy] unchanged and already covered by [FastAccessStrategyTest];
 * this only has to prove the one thing REGISTER changes: where the chain starts.
 */
class RegisterStrategyTest : BehaviorSpec({

    val strategy = RegisterStrategy()

    given("the intent") {
        then("is REGISTER") {
            strategy.intent shouldBe AuthIntent.REGISTER
        }
    }

    given("Start, even with a linked device credential and other active methods") {
        val acc = account(method("device", "loa2", details = deviceDetails()), method("sms", "loa2"))

        then("skips PreferredAuth/AuthChoice entirely and goes straight to identification") {
            val decision = strategy.next(FastAccessState.Start, JourneyEvent.Started, ctx(account = acc))
            decision.shouldBeInstanceOf<Decision.Advance>()
            (decision as Decision.Advance).to.shouldBeInstanceOf<FastAccessState.Identifying>()
        }
    }

    given("Start, no account known yet") {
        then("behaves exactly like FAST_ACCESS in this case - both fall to identification") {
            val decision = strategy.next(FastAccessState.Start, JourneyEvent.Started, ctx(account = null))
            decision.shouldBeInstanceOf<Decision.Advance>()
            (decision as Decision.Advance).to.shouldBeInstanceOf<FastAccessState.Identifying>()
        }
    }
})
