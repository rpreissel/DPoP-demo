package com.example.dpop.orchestrator.domain.journey

import com.example.dpop.orchestrator.domain.ChannelType
import com.example.dpop.account.AccountProfile
import com.example.dpop.orchestrator.domain.policy.AuthEvidence
import com.example.dpop.orchestrator.domain.policy.AuthPolicy
import com.example.dpop.orchestrator.domain.ToolCatalog
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.ToolId
import com.example.dpop.orchestrator.domain.FeatureFlags

/**
 * Everything a strategy may look at. Read-only by construction: [policy] and [catalog] answer
 * questions, they change nothing.
 */
data class JourneyContext(
    /**
     * Which facade opened this channel (docs/02-domaenenmodell.md #4). Named explicitly rather
     * than inferred from [bindingKeyRef] being null - a strategy that needs to branch on channel
     * type (e.g. a Web-only enrollment obligation) should read a declared fact, not an implicit
     * side effect of a different, APP-only concept.
     */
    val channel: ChannelType,
    /** The account this journey concerns, once resolved - `null` before any identification/lookup. */
    val account: AccountProfile?,
    /** What this channel's session has already proven. */
    val evidence: AuthEvidence,
    /** The channel's durable lower bound - never a single run's target (that lives in the state). */
    val acrFloor: AcrLevel,
    /** The calling device's DPoP-proven key thumbprint - null on a KEYCLOAK channel, which has none. */
    val bindingKeyRef: String?,
    /** The account this device is durably linked to, if any - independent of this channel. */
    val linkedAccountId: Long?,
    /** True while this journey runs as another one's precondition (docs/04-orchestrierung.md #6). */
    val isSubJourney: Boolean,
    /** Answers ACR/candidate questions - see [AuthPolicy]. */
    val policy: AuthPolicy,
    /** The full tool catalog, for descriptor lookups. */
    val catalog: ToolCatalog,
    /**
     * toolIds this channel may currently offer: the client's own declared support intersected with
     * whatever the backend hasn't killed-switched off (docs/03-tool-architektur.md, availability).
     * [CandidateTools] filters every candidate list through this - never derive an offer from
     * [catalog] alone.
     */
    val availableTools: Set<ToolId>,
    /**
     * Runtime feature flags currently enabled (see [FeatureFlags] for the known names), resolved
     * once here rather than injected into a strategy directly - a strategy DECIDES, it never
     * depends on a `@Service` itself (`IntentStrategy`'s own class doc, enforced by
     * `OrchestratorArchitectureTest`). One shared, generic set rather than a new named
     * `JourneyContext` property per flag, so adding a future experiment never touches this
     * widely-used data class again.
     */
    val featureFlags: Set<String> = emptySet()
) {
    /**
     * The resolved account, for the states that structurally cannot be reached without one (a
     * step-up, a method change, a deletion). Crashes rather than returning `null`, because at
     * those states a missing account is a broken state machine, not a case to branch on - a
     * strategy that must tolerate its absence reads [account] directly instead.
     */
    fun requireAccount(): AccountProfile =
        checkNotNull(account) { "Strategy asked for an account before one was resolved" }

    /**
     * The ACR this channel's evidence resolves to right now - convenience for a strategy building
     * a [Transition.RequireSubJourney]'s own `seedWith` (the sub-journey's `startingAcr`), so every
     * such call site doesn't have to repeat `policy.resolveAcr(evidence, account)` itself.
     */
    val currentAcr: AcrLevel get() = policy.resolveAcr(evidence, account)
}
