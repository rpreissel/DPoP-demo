package com.example.dpop.orchestrator.domain.journey

import com.example.dpop.account.AccountProfile
import com.example.dpop.account.AuthMethodView
import com.example.dpop.orchestrator.domain.AcrLevels
import com.example.dpop.orchestrator.domain.AuthIntent
import com.example.dpop.orchestrator.domain.ToolCatalog
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.CallerKeyBinding
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.ToolCategory

/*
 * At which level a credential counts, which device link follows from succeeding, and what else falls
 * when a credential or an attribute goes. Pure, like AccountRules.kt: `JourneyActionExecutor` reads,
 * asks here, and writes (docs/adr/ADR-040-fachkern-im-paket-domain.md).
 */

/**
 * The level a new credential or an attested anchor is written under: what the session had ALREADY
 * established before this completion. "None" only ever means literally nothing backs the session yet
 * (REGISTER "Enrollment zuerst" with no identification) - then the flat baseline, never the tool's
 * own declared strength: a self-registered credential with nothing corroborating it is exactly the
 * self-asserted case, however strong the tool's own `maxAcr` is (ADR-5). And never above what was
 * established - that would be the self-escalation ADR-5 exists to prevent.
 */
fun levelToWriteUnder(sessionAcr: AcrLevel): AcrLevel =
    if (sessionAcr == AcrLevel.NONE) AcrLevels.DEFAULT_REQUIRED_ACR else sessionAcr

/**
 * The level a proof counts at: what the tool achieved, capped by what the credential that was USED
 * was enrolled under (ADR-5). For a method with several instances (devices, KOBIL installations) the
 * used one is the one on the caller's key - the same rule the tool chose it by ([keyBinding]).
 * Should that still leave more than one, the lowest cap applies: a level is never granted on a guess
 * (review 2026-09, Phase F).
 */
fun proofLevel(active: List<AuthMethodView>, keyBinding: CallerKeyBinding?, bindingKeyRef: String?, achieved: AcrLevel?): AcrLevel {
    check(active.isNotEmpty()) { "No active instance to count the proof against" }
    val used = active.filter { keyBinding?.livesOn(it.details, bindingKeyRef) ?: true }.ifEmpty { active }
    val cap = used.map { it.enrolledUnderAcr?.let(AcrLevel::of) }.reduce { a, b -> AcrLevel.min(a, b) }
    return AcrLevel.min(achieved, cap)
}

/**
 * Whether succeeding at [intent] links this device to [accountId] on its own. Never as a REBIND:
 * succeeding at an ordinary flow is consent to be recognized by this account next time, not consent
 * to take the device away from another one (which also revokes that account's device credentials).
 * A rebind only happens down the explicit route, after the user answered a prompt that says so
 * (`ConfirmDeviceRebind`) - a property of the act, not of each strategy remembering to ask.
 */
fun linksDeviceImplicitly(intent: AuthIntent, linkedTo: Long?, accountId: Long): Boolean =
    intent.bindsDeviceImplicitly && (linkedTo == null || linkedTo == accountId)

/**
 * The active methods of [account] whose credential physically lives on [bindingKeyRef] - exactly
 * those that stop being usable when that key moves to another account. Resolved by
 * `(method, IDENTIFIED_AUTH)`, the pair that names one concrete procedure; by method name alone the
 * enrollment tool would answer just as readily, from the wrong declaration.
 */
fun credentialsLivingOn(account: AccountProfile?, bindingKeyRef: String, catalog: ToolCatalog): List<AuthMethodView> =
    account?.activeAuthenticationMethods.orEmpty().filter { method ->
        val binding = catalog.descriptors()
            .firstOrNull { it.role == MethodRole.IDENTIFIED_AUTH && it.method == method.method }
            ?.keyBinding
        binding != null && binding.livesOn(method.details, bindingKeyRef)
    }

/**
 * What else falls when a credential or an attribute goes. Nobody declares this dependency as such:
 * it is read off the `requires` gates that are already there. Revoking a method retracts the claims
 * it asserted, and any method whose `requires` named one of those loses its own precondition - so it
 * cannot stand either, and its claims are gone in turn. Hence a fixpoint rather than a single pass.
 * A claim that survives - an account-owned anchor like EMAIL, or one another active method also
 * asserts - keeps its dependents alive.
 *
 * [claimedTypes] answers which attribute types a method instance asserted (a lookup in the claim
 * log); everything else is read from [account] and [catalog].
 */
class MethodDependencies(
    private val account: AccountProfile,
    private val catalog: ToolCatalog,
    private val claimedTypes: (AuthMethodView) -> Set<AttributeType>,
) {
    /** The active credentials that cannot outlive [target] - transitively. */
    fun dependentsOf(target: AuthMethodView): List<AuthMethodView> {
        val lost = claimedBy(listOf(target)) - claimedBy(account.activeAuthenticationMethods.filter { it.id != target.id })
        return dependentsOfLostClaims(lost, falling = listOf(target))
    }

    /**
     * The fixpoint itself, shared by both causes of a claim going away: a revoked credential that
     * asserted it, or an attribute withdrawn outright (then [falling] is empty). Returns only the
     * NEW casualties, not [falling] itself.
     */
    fun dependentsOfLostClaims(lost: Set<AttributeType>, falling: List<AuthMethodView>): List<AuthMethodView> {
        val casualties = falling.toMutableList()
        var lostSoFar = lost
        while (true) {
            val fallingIds = casualties.map { it.id }.toSet()
            val standing = account.activeAuthenticationMethods.filter { it.id !in fallingIds }
            val next = standing.filter { instance ->
                catalog.descriptors()
                    .filter { it.method == instance.method && it.role.category == ToolCategory.ENROLL }
                    .any { descriptor -> descriptor.requires.any { it.attributeType in lostSoFar } }
            }
            if (next.isEmpty()) return casualties.drop(falling.size)
            casualties += next
            // A casualty takes its own claims with it - unless something still standing asserts
            // the same type, which is why this is recomputed rather than unioned.
            val stillStanding = account.activeAuthenticationMethods.filter { it.id !in casualties.map { c -> c.id }.toSet() }
            lostSoFar = lostSoFar + (claimedBy(casualties) - claimedBy(stillStanding))
        }
    }

    /** The account as it would be with [falling] deactivated - what the floor check runs against. */
    fun without(falling: Collection<AuthMethodView>): AccountProfile {
        val ids = falling.map { it.id }.toSet()
        return account.copy(authenticationMethods = account.authenticationMethods.map { if (it.id in ids) it.copy(active = false) else it })
    }

    private fun claimedBy(instances: List<AuthMethodView>): Set<AttributeType> = instances.flatMap(claimedTypes).toSet()
}
