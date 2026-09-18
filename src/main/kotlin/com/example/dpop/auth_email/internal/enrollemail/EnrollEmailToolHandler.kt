package com.example.dpop.auth_email.internal.enrollemail

import com.example.dpop.auth_email.EnrollEmailDescriptor
import com.example.dpop.tool_api.EMAIL_ANCHOR_ENROLLMENT
import com.example.dpop.tool_spi.ToolOutcome
import java.util.UUID
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * toolId=enroll-email: turns the account's already confirmed address into an authentication
 * method. A one-shot - activating it completes it - because there is nothing left to prove:
 * `confirm-email` established control over the address, and asking for a second code would only
 * repeat that proof. The descriptor's `requires` gate (`ClaimRequirement(EMAIL, PROVEN)`) is what
 * guarantees the anchor is there, checked by the orchestrator before this ever runs.
 *
 * The credential IS that anchor ([EMAIL_ANCHOR_ENROLLMENT]), so this module owns no enrollment
 * table. No `amr`: nothing was proven in this run (see [EnrollEmailDescriptor]).
 */
@Component
class EnrollEmailToolHandler(
    private val descriptor: EnrollEmailDescriptor,
    private val toolDataRepository: EnrollEmailToolSessionRepository
) {

    @Transactional
    fun start(toolSessionId: UUID): ToolOutcome {
        toolDataRepository.save(EnrollEmailToolSession(toolSessionId = toolSessionId))
        return completed()
    }

    /**
     * A re-read after completion returns the same outcome: the tool has exactly one state, so
     * there is no step to describe and nothing a client could still submit.
     */
    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        checkNotNull(toolDataRepository.findById(toolSessionId).orElse(null)) {
            "Unknown enroll-email tool session: $toolSessionId"
        }
        return completed()
    }

    private fun completed() = ToolOutcome.Completed.Enrolled(
        enrollmentRef = EMAIL_ANCHOR_ENROLLMENT,
        achievedAcr = descriptor.maxAcr,
        factorTypes = descriptor.factorTypes
    )
}
