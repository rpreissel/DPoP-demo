package com.example.dpop.orchestrator.session

import com.example.dpop.orchestrator.journey.FeatureFlagProvider
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Read live, never cached, same reasoning as `ToolAvailabilityService` (`orchestrator.tool`): a
 * flip must take effect on the very next brand-new journey, not just after a restart.
 *
 * Implements [FeatureFlagProvider] so `JourneyService` picks the flags up generically. Adding a
 * flag therefore touches nothing here: declare its name in
 * [com.example.dpop.orchestrator.journey.FeatureFlags], read it in the strategy that cares, and
 * flip it through this service.
 */
@Service
@Transactional
class FeatureFlagService(private val repository: FeatureFlagRepository) : FeatureFlagProvider {

    /** No row means off - the flag's documented default, never an error. */
    fun isEnabled(flagKey: String): Boolean = repository.findByIdOrNull(flagKey)?.enabled ?: false

    override fun activeFlags(): Set<String> =
        repository.findByEnabledTrue().mapNotNull { it.flagKey }.toSet()

    fun setEnabled(flagKey: String, enabled: Boolean, reason: String? = null) {
        val flag = repository.findByIdOrNull(flagKey) ?: FeatureFlag(flagKey = flagKey)
        flag.enabled = enabled
        flag.reason = reason
        flag.updatedAt = Instant.now()
        repository.save(flag)
    }
}
