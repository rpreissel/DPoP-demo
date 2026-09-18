package com.example.dpop.orchestrator.journey

/**
 * Names of the runtime feature flags a strategy may read off [JourneyContext.featureFlags] - one
 * shared, generic `Set<String>` field rather than a new named `JourneyContext` property per flag
 * (which would otherwise touch this widely-used data class again for every future experiment).
 * `JourneyService` is the only place that resolves the actual `@Service`-backed value (e.g.
 * `FeatureFlagService`) and turns it into membership in this set - see `IntentStrategy`'s own
 * class doc for why a strategy itself is never allowed to hold such a service directly.
 */
object FeatureFlags {
    /** REGISTER's "Enrollment zuerst" experiment (`FeatureFlagService`, docs/04-orchestrierung.md). */
    const val REGISTER_ENROLL_FIRST = "register-enroll-first"
}

/**
 * A `@Service` that backs a runtime feature flag implements this to contribute its own currently-
 * active flag name(s) (see [FeatureFlags]) - `JourneyService` collects every bean implementing
 * this (`List<FeatureFlagProvider>`, same idiom as its own `List<IntentStrategy<*>>`) into
 * [JourneyContext.featureFlags], so a new flag is purely additive: implement this on its own
 * service, nothing in `JourneyService` itself ever needs to change.
 */
fun interface FeatureFlagProvider {
    /** Empty when nothing this provider owns is currently active - never null, never a single nullable flag. */
    fun activeFlags(): Set<String>
}
