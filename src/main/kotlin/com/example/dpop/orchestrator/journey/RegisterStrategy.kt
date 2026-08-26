package com.example.dpop.orchestrator.journey

import org.springframework.stereotype.Component

/**
 * Deliberately fresh identification, even on an already linked device (docs/04-orchestrierung.md
 * #2): the device link lookup is suppressed and no existing account binding is ever offered.
 *
 * It does NOT force a second account - the same KVNR still finds the same account again. "I want
 * to identify myself anew here" is a different goal from "get me in", which is why it is its own
 * intent rather than a boolean on FAST.
 *
 * Its states ARE FAST's states from the identification one on, so it shares [FastState] instead of
 * duplicating it. The only thing it changes is where the ladder starts: [firstOffer] skips the first two states
 * and 2, which is precisely what this intent means. Everything after identification - the email
 * obligation, the enrolment state, the finish condition - is FAST's behaviour unchanged.
 */
@Component
class RegisterStrategy : FastStrategy() {

    override val intent: AuthIntent = AuthIntent.REGISTER

    override fun firstOffer(ctx: JourneyContext): Decision = offerIdentification(ctx)
}
