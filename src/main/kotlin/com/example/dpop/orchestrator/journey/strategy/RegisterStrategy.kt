package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.account.AccountProfile
import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.JourneyContext
import com.example.dpop.orchestrator.journey.Transition
import com.example.dpop.orchestrator.journey.state.FastAccessState
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.tool_spi.MethodRole
import org.springframework.stereotype.Component

/**
 * Deliberately fresh identification, even on an already linked device (docs/04-orchestrierung.md
 * #2): the device link lookup is suppressed and no existing account binding is ever offered.
 *
 * It does NOT force a second account - the same KVNR still finds the same account again. "I want
 * to identify myself anew here" is a different goal from "get me in", which is why it is its own
 * intent rather than a boolean on FAST.
 *
 * Its states ARE FAST's states from the identification one on, so it shares [FastAccessState] instead of
 * duplicating it. The only thing it changes is where the fallback chain starts: [firstOffer] skips the first two states
 * and 2, which is precisely what this intent means. Everything after identification - the email
 * obligation, the enrolment state, the finish condition - is FAST's behaviour unchanged, except for
 * the additional, Web-channel-only password obligation below.
 */
@Component
class RegisterStrategy : FastAccessStrategy() {

    override val intent: AuthIntent = AuthIntent.REGISTER

    override fun firstOffer(ctx: JourneyContext): Transition = offerIdentification(ctx)

    /**
     * Web-only third obligation (docs/04-orchestrierung.md #8, [FastAccessState.PasswordObligation]'s
     * own KDoc): registering via the KEYCLOAK channel must always end up with a password
     * credential, in addition to the shared email obligation - `enroll-sms` stays a free choice,
     * only the `password` method is unconditionally required.
     *
     * Wraps the base decision rather than re-deriving reachability/sufficiency itself: only ever
     * intercepts the one outcome that means "this run would finish right now" ([Transition.
     * Authenticated]) and redirects it - every other outcome (still short of the floor, the email
     * obligation still open) is none of this override's business and passes through unchanged.
     * This is also why the order falls out correctly without this override needing to know about
     * it: the base already only returns [Transition.Authenticated] once any email obligation is
     * discharged, so password is necessarily checked last.
     *
     * Deliberately only overrides `afterEnrollment`, never `afterProof`: when [Identifying] rediscovers
     * an EXISTING account that already has a sufficient active method (`afterIdentification` ->
     * `AuthChoice` -> `afterProof`), that path finishes without ever calling this override - same as
     * the pre-existing email obligation, which explicitly never applies there either (`afterProof`'s
     * own KDoc: "an existing account that merely logs in is never retroactively blocked"). A
     * rediscovered, already-set-up account is treated as an ordinary login, not a fresh registration -
     * consistent, not a gap (confirmed 2026-09-09).
     */
    override fun afterEnrollment(ctx: JourneyContext, emailObligation: Boolean): Transition {
        val base = super.afterEnrollment(ctx, emailObligation)
        if (base != Transition.Authenticated || ctx.channel != ChannelSession.Channel.KEYCLOAK) return base

        val account = ctx.requireAccount()
        if (account.activeAuthenticationMethods.any { it.method == PASSWORD_METHOD }) return base
        val candidates = passwordEnrollmentCandidates(ctx)
        return if (candidates.isNotEmpty()) Transition.To(FastAccessState.PasswordObligation(candidates)) else base
    }

    /**
     * The `password` METHOD is the one intentionally hardcoded name in this strategy - never a
     * toolId, unlike a naive `"enroll-password"` literal would be: which concrete tool provides it
     * is a catalog/availability question like every other candidate list ([CandidateTools]'s own
     * pattern), because App and Web could register different tools for the same method. Empty when
     * no such tool is currently offerable (unavailable, kill-switched) - the caller falls through
     * to the shared behaviour rather than dead-ending on an obligation nothing can fulfil.
     */
    private fun passwordEnrollmentCandidates(ctx: JourneyContext): List<String> =
        ctx.catalog.descriptors()
            .filter { it.role == MethodRole.ENROLLMENT && it.method == PASSWORD_METHOD }
            .map { it.toolId }
            .filter { it in ctx.availableTools }

    private companion object {
        const val PASSWORD_METHOD = "password"
    }
}
