package com.example.dpop.auth_password.internal.authpassworduse
import com.example.dpop.auth_password.internal.PasswordHasher
import com.example.dpop.auth_password.internal.AuthPasswordEnrollmentRepository

import com.example.dpop.auth_password.AuthPasswordUseDescriptor
import com.example.dpop.auth_password.PASSWORD_ENROLLMENT_TYPE
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.ToolOutcome
import com.example.dpop.tool_spi.UnresolvableReferenceException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * toolId=auth-password, mirroring AuthSmsUseToolHandler (docs/06-ablaeufe.md #3). [start]'s
 * [enrollmentRef] must be the account's active password enrollment reference - resolved and
 * null-checked by AuthPasswordToolController before calling this (never null here), since this
 * module never reads `account` itself. Only the password itself is asked for: the account is
 * already resolved (device-linked case), same shape as auth-sms only needing the TAN.
 *
 * Pure business logic; self-description lives in [AuthPasswordUseDescriptor] (DPoP-demo-vun).
 * Delegates the input decision to [AuthPasswordUseFlow].
 */
@Component
class AuthPasswordUseToolHandler(
    private val descriptor: AuthPasswordUseDescriptor,
    private val toolDataRepository: AuthPasswordUseToolDataRepository,
    private val enrollmentRepository: AuthPasswordEnrollmentRepository
) {

    @Transactional
    fun start(toolSessionId: UUID, enrollmentRef: EnrollmentRef): ToolOutcome {
        if (enrollmentRef.type != PASSWORD_ENROLLMENT_TYPE) {
            throw UnresolvableReferenceException("Unerwarteter Enrollment-Typ: ${enrollmentRef.type}")
        }
        val enrollmentId = enrollmentRef.id.toLongOrNull()
            ?: throw UnresolvableReferenceException("Ungueltige Enrollment-Referenz: ${enrollmentRef.id}")
        if (!enrollmentRepository.existsById(enrollmentId)) {
            throw UnresolvableReferenceException("Password-Enrollment nicht gefunden: ${enrollmentRef.id}")
        }

        toolDataRepository.save(
            AuthPasswordUseToolData(
                toolSessionId = toolSessionId,
                enrollmentRefType = enrollmentRef.type,
                enrollmentRefId = enrollmentRef.id
            )
        )
        return outcomeFor()
    }

    /** Called directly by AuthPasswordToolController, not generically dispatched (docs/08-projektrahmen.md A11). */
    @Transactional
    fun patch(toolSessionId: UUID, password: String?): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-password tool session: $toolSessionId" }

        return when (val decision = AuthPasswordUseFlow.decide(AuthPasswordUseInput(password))) {
            AuthPasswordUseDecision.Unchanged -> outcomeFor()
            is AuthPasswordUseDecision.Check -> {
                val enrollmentId = data.enrollmentRefId!!.toLong()
                val enrollment = enrollmentRepository.findByIdOrNull(enrollmentId)
                    ?: return ToolOutcome.Failed("Passwort ungueltig")

                if (PasswordHasher.matches(decision.password, enrollment.passwordHash)) {
                    ToolOutcome.Completed.Authenticated(
                        amr = listOf(descriptor.method),
                        achievedAcr = descriptor.maxAcr,
                        factorTypes = descriptor.factorTypes
                    )
                } else {
                    ToolOutcome.Failed("Passwort ungueltig")
                }
            }
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-password tool session: $toolSessionId" }
        return outcomeFor()
    }

    private fun outcomeFor(): ToolOutcome.InProgress {
        val (step, fields) = AuthPasswordUseFlow.describe()
        return ToolOutcome.InProgress(nextStep = step, data = fields)
    }
}
