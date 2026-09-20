package com.example.dpop.orchestrator.journey

import com.example.dpop.orchestrator.journey.state.AuthChoice
import com.example.dpop.orchestrator.journey.state.FastAccessState
import com.example.dpop.tool_spi.ToolId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class RequireSubJourneyInvariantTest : BehaviorSpec({
    given("a sub-journey request") {
        `when`("resuming at a state that carries an offer") {
            then("it is rejected at construction - the offer must be recomputed on return") {
                val e = shouldThrow<IllegalArgumentException> {
                    Transition.RequireSubJourney(
                        intent = AuthIntent.RE_IDENTIFY,
                        seedWith = FastAccessState.Start,
                        resumeWith = AuthChoice(offered = listOf(ToolId("auth-sms")))
                    )
                }
                e.message shouldContain "recomputed"
            }
        }
        `when`("resuming at a recomputing state") {
            then("it is accepted") {
                Transition.RequireSubJourney(
                    intent = AuthIntent.RE_IDENTIFY,
                    seedWith = FastAccessState.Start,
                    resumeWith = FastAccessState.Start
                ).resumeWith shouldBe FastAccessState.Start
            }
        }
    }
})
