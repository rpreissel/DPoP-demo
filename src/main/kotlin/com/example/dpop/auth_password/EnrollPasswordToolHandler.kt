package com.example.dpop.auth_password

import com.example.dpop.auth_password.internal.AuthPasswordEnrollment
import com.example.dpop.auth_password.internal.AuthPasswordEnrollmentRepository
import com.example.dpop.auth_password.internal.EnrollPasswordToolData
import com.example.dpop.auth_password.internal.EnrollPasswordToolDataRepository
import com.example.dpop.auth_password.internal.PasswordHasher
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ToolOutcome
import com.example.dpop.tool_spi.demoData
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * toolId=enroll-password: registers a password as a knowledge factor, mirroring enroll-sms
 * (docs/06-ablaeufe.md #4) but without an out-of-band confirmation step - a chosen password is
 * self-verifying, so Completed.Enrolled fires directly from one PATCH. No identifier field: the
 * account's confirmed email is the identifier (requiresConfirmedEmail precondition), so this
 * tool only ever asks for the password itself.
 * Implements ToolDescriptor directly rather than through a wrapper interface
 * (docs/03-tool-architektur.md #2) - ToolHandlerRegistry collects `List<ToolDescriptor>`
 * straight from Spring.
 */
@Component
class EnrollPasswordToolHandler(
    private val toolDataRepository: EnrollPasswordToolDataRepository,
    private val enrollmentRepository: AuthPasswordEnrollmentRepository
) : ToolDescriptor {

    override val toolId = "enroll-password"
    override val role = MethodRole.ENROLLMENT
    override val methodFamily = PASSWORD_METHOD
    override val factorTypes = setOf(FactorType.KNOWLEDGE)
    override val maxAcr = "loa1"
    override val requiresConfirmedEmail = true

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
            amr = listOf("password"),
            achievedAcr = maxAcr,
            factorTypes = factorTypes
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
