package com.example.dpop.auth_password.internal

import com.example.dpop.auth_password.AuthPasswordLookupDescriptor
import com.example.dpop.auth_password.DEMO_PASSWORD
import com.example.dpop.tool_spi.DEMO_EMAIL
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.ToolOutcome
import com.example.dpop.tool_spi.demoData
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * toolId=auth-password-lookup: "login ohne DPoP" (docs/04-orchestrierung.md) - single-step,
 * self-verifying like AuthPasswordUseToolHandler, but takes email+password together since the
 * account isn't known via the channel yet. This module cannot resolve email itself (auth_password
 * may only depend on tool_spi, docs/08-projektrahmen.md A11) - the controller resolves it to an
 * accountId/EnrollmentRef via AccountService and passes the result into [patch].
 *
 * Pure business logic; self-description lives in [AuthPasswordLookupDescriptor] (DPoP-demo-vun).
 */
@Component
class AuthPasswordLookupToolHandler(
    private val descriptor: AuthPasswordLookupDescriptor,
    private val toolDataRepository: AuthPasswordLookupToolDataRepository,
    private val enrollmentRepository: AuthPasswordEnrollmentRepository
) {

    @Transactional
    fun start(toolSessionId: UUID): ToolOutcome {
        toolDataRepository.save(AuthPasswordLookupToolData(toolSessionId = toolSessionId))
        return ToolOutcome.InProgress(nextStep = "auth", data = mapOf("missingFields" to listOf("email", "password"), demoData("email" to DEMO_EMAIL, "password" to DEMO_PASSWORD)))
    }

    /**
     * [accountId]/[enrollmentRef] are null when the email is unknown or has no active password
     * method - a constant-shape failure either way, so the response never reveals whether the
     * email exists (enumeration protection, docs/04-orchestrierung.md).
     */
    @Transactional
    fun patch(toolSessionId: UUID, email: String?, password: String?, accountId: Long?, enrollmentRef: EnrollmentRef?): ToolOutcome {
        checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-password-lookup tool session: $toolSessionId" }

        val missing = buildList {
            if (email.isNullOrBlank()) add("email")
            if (password.isNullOrBlank()) add("password")
        }
        if (missing.isNotEmpty()) {
            return ToolOutcome.InProgress(nextStep = "auth", data = mapOf("missingFields" to missing, demoData("email" to DEMO_EMAIL, "password" to DEMO_PASSWORD)))
        }

        val enrollment = enrollmentRef
            ?.takeIf { it.type == "auth_password_enrollment" }
            ?.id?.toLongOrNull()
            ?.let { enrollmentRepository.findByIdOrNull(it) }

        return if (accountId != null && enrollment != null && PasswordHasher.matches(password!!, enrollment.passwordHash)) {
            ToolOutcome.Completed.Authenticated(
                amr = listOf(descriptor.method),
                achievedAcr = descriptor.maxAcr,
                factorTypes = descriptor.factorTypes,
                accountId = accountId
            )
        } else {
            ToolOutcome.Failed("E-Mail oder Passwort ungueltig")
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-password-lookup tool session: $toolSessionId" }
        return ToolOutcome.InProgress(nextStep = "auth", data = mapOf("missingFields" to listOf("email", "password"), demoData("email" to DEMO_EMAIL, "password" to DEMO_PASSWORD)))
    }
}
