package com.example.dpop.orchestrator.domain.journey

import com.example.dpop.orchestrator.journey.JourneyService
import com.example.dpop.account.AccountProfile
import com.example.dpop.orchestrator.domain.journey.state.JourneyState
import com.example.dpop.orchestrator.domain.AcrLevels
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.orchestrator.domain.AuthIntent

/**
 * The ACR floor for any action that only ever touches an account's OWN credentials/data, never a
 * stranger's - anti-self-escalation (a hijacked loa1 session must not act as if it had proved
 * more than it did) shared by every such gate ([Action.DeleteAccount.requiredAcr],
 * `ManageAuthMethodsStrategy.gate`) so the rule has exactly one definition instead of a
 * per-strategy constant.
 *
 * loa1 suffices for an account that was never identified ([AccountProfile.personId] `== null` -
 * the "Enrollment zuerst" case, docs/04-orchestrierung.md REGISTER): there is no bound
 * identity/Stammdaten a hijacked loa1 session could reach beyond what the enrollment itself
 * already exposed. Every identified account still requires loa2.
 *
 * Keyed on `personId`, deliberately not on the account's current `acr`: a never-identified
 * account can in fact never reach loa2 in the first place (`DefaultAuthPolicy.combinedAcr` caps
 * any MFA bump at the highest `enrolledUnderAcr` among its methods, and every method such an
 * account enrolls is itself capped at loa1 - there is no path to a higher `enrolledUnderAcr`
 * without an identification first). This floor therefore never grants more than such an account
 * could legitimately reach anyway; it only stops demanding a level it could never clear. That the
 * mailbox alone then suffices for destructive self-service is deliberate (ADR-37, review M-7).
 */
fun selfServiceAcrFloor(account: AccountProfile?): AcrLevel =
    if (account?.personId == null) AcrLevels.DEFAULT_REQUIRED_ACR else AcrLevel.LOA2

/**
 * The SPI each intent implements - symmetric to `tool_spi`, where tools describe themselves.
 *
 * A strategy DECIDES, it never ACTS: it gets a read-only [JourneyContext] and names an [Action]
 * for JourneyService to execute. Everything with a side effect - creating accounts, recording
 * evidence, writing device links, capping ACR - is executed centrally by [JourneyService] and is
 * not reachable from here.
 *
 * `transition` is the transition function (classical automaton terminology: `δ`) - the one and
 * only place a strategy answers "what happens next". "First offer", "a tool just completed", "a
 * tool was abandoned", "an action just finished" and "a sub-journey came back" are all the same
 * question with a different [JourneyEvent], not four separate methods.
 */
interface IntentStrategy<S : JourneyState> {
    /** Which [AuthIntent] this strategy implements - one bean per entry in that enum. */
    val intent: AuthIntent

    /** Where a fresh journey of this intent begins, before any event has been seen. */
    fun initialState(ctx: JourneyContext): S

    /**
     * The one and only transition. A completed tool's outcome answers "what did it establish" via
     * [Transition.Perform]: JourneyService executes the returned [Action], refreshes
     * [JourneyContext] from the result, and calls this again with [JourneyEvent.ActionCompleted] -
     * so a strategy's own follow-up logic (e.g. "is the floor satisfied now?") always sees the
     * POST-action context, never the stale one the tool outcome arrived with.
     */
    fun transition(state: S, event: JourneyEvent, ctx: JourneyContext): Transition
}
