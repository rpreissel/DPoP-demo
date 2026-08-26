package com.example.dpop.auth_password.internal

import com.example.dpop.auth_password.DEMO_PASSWORD
import com.example.dpop.auth_password.EnrollPasswordDescriptor
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.ToolOutcome
import com.example.dpop.tool_spi.demoData
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
        return ToolOutcome.InProgress(nextStep = "enroll", data = mapOf("missingFields" to listOf("password"), demoData("password" to DEMO_PASSWORD)))
    }

    /** Called directly by EnrollPasswordToolController, not generically dispatched (docs/08-projektrahmen.md A11). */
    @Transactional
    fun patch(toolSessionId: UUID, password: String?): ToolOutcome {
        checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown enroll-password tool session: $toolSessionId" }

        val value = password
            ?: return ToolOutcome.InProgress(nextStep = "enroll", data = mapOf("missingFields" to listOf("password"), demoData("password" to DEMO_PASSWORD)))
        if (value.length < MIN_PASSWORD_LENGTH) {
            throw IllegalArgumentException("Passwort zu kurz (mindestens $MIN_PASSWORD_LENGTH Zeichen)")
        }

        val enrollment = enrollmentRepository.save(AuthPasswordEnrollment(passwordHash = PasswordHasher.hash(value)))
        return ToolOutcome.Completed.Enrolled(
            enrollmentRef = EnrollmentRef(type = "auth_password_enrollment", id = enrollment.id.toString()),
            amr = listOf(descriptor.method),
            achievedAcr = descriptor.maxAcr,
            factorTypes = descriptor.factorTypes
        )
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown enroll-password tool session: $toolSessionId" }
        return ToolOutcome.InProgress(nextStep = "enroll", data = mapOf("missingFields" to listOf("password"), demoData("password" to DEMO_PASSWORD)))
    }

    companion object {
        private const val MIN_PASSWORD_LENGTH = 8
    }
}
