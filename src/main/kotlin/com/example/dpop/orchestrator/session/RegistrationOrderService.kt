package com.example.dpop.orchestrator.session

import com.example.dpop.orchestrator.journey.FeatureFlagProvider
import com.example.dpop.orchestrator.journey.FeatureFlags
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Read live, never cached, same reasoning as `ToolAvailabilityService` (`orchestrator.tool`): a
 * flip must take effect on the very next brand-new REGISTER journey, not just after a restart.
 * Implements [FeatureFlagProvider] so `JourneyService` picks it up generically (`List<
 * FeatureFlagProvider>`) instead of depending on this concrete service by name.
 */
@Service
@Transactional
class RegistrationOrderService(private val repository: RegistrationOrderRepository) : FeatureFlagProvider {

    fun isEnrollFirst(): Boolean = repository.findByIdOrNull(RegistrationOrderSetting.DEFAULT_ID)?.enrollFirst ?: false

    override fun activeFlags(): Set<String> = if (isEnrollFirst()) setOf(FeatureFlags.REGISTER_ENROLL_FIRST) else emptySet()

    fun setEnrollFirst(enrollFirst: Boolean) {
        val entry = repository.findByIdOrNull(RegistrationOrderSetting.DEFAULT_ID) ?: RegistrationOrderSetting()
        entry.enrollFirst = enrollFirst
        entry.updatedAt = Instant.now()
        repository.save(entry)
    }
}
