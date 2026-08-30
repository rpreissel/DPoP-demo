package com.example.dpop.auth_password.internal.enrollpassword
import com.example.dpop.auth_password.internal.PasswordHasher
import com.example.dpop.auth_password.internal.AuthPasswordEnrollmentRepository
import com.example.dpop.auth_password.internal.AuthPasswordEnrollment

import com.example.dpop.auth_password.PASSWORD_ENROLLMENT_TYPE
import com.example.dpop.auth_password.EnrollPasswordDescriptor
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * toolId=enroll-password: registers a password as a knowledge factor, mirroring enroll-sms
 * (docs/06-ablaeufe.md #4) but without an out-of-band confirmation step - a chosen password is
 * self-verifying, so Completed.Enrolled fires directly from one PATCH.
 *
 * Pure business logic; self-description lives in [EnrollPasswordDescriptor] (DPoP-demo-vun).
 * Delegates the input decision to [EnrollPasswordFlow].
 */
@Component
class EnrollPasswordToolHandler(
    private val descriptor: EnrollPasswordDescriptor,
    private val toolDataRepository: EnrollPasswordToolDataRepository,
    private val enrollmentRepository: AuthPasswordEnrollmentRepository
) {

    /** Called directly by EnrollPasswordToolController; nothing needs resolving before this can start. */
    @Transactional
    fun start(toolSessionId: UUID): ToolOutcome {
        toolDataRepository.save(EnrollPasswordToolData(toolSessionId = toolSessionId))
        return outcomeFor()
    }

    /** Called directly by EnrollPasswordToolController, not generically dispatched (docs/08-projektrahmen.md A11). */
    @Transactional
    fun patch(toolSessionId: UUID, password: String?): ToolOutcome {
        checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown enroll-password tool session: $toolSessionId" }

        return when (val decision = EnrollPasswordFlow.decide(EnrollPasswordInput(password))) {
            EnrollPasswordDecision.Unchanged -> outcomeFor()
            is EnrollPasswordDecision.TooShort ->
                throw IllegalArgumentException("Passwort zu kurz (mindestens ${EnrollPasswordFlow.MIN_PASSWORD_LENGTH} Zeichen)")

            is EnrollPasswordDecision.Enroll -> {
                val enrollment = enrollmentRepository.save(AuthPasswordEnrollment(passwordHash = PasswordHasher.hash(decision.password)))
                ToolOutcome.Completed.Enrolled(
                    enrollmentRef = EnrollmentRef(type = PASSWORD_ENROLLMENT_TYPE, id = enrollment.id.toString()),
                    amr = listOf(descriptor.method),
                    achievedAcr = descriptor.maxAcr,
                    factorTypes = descriptor.factorTypes
                )
            }
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown enroll-password tool session: $toolSessionId" }
        return outcomeFor()
    }

    private fun outcomeFor(): ToolOutcome.InProgress {
        val (step, fields) = EnrollPasswordFlow.describe()
        return ToolOutcome.InProgress(nextStep = step, data = fields)
    }
}
