package com.example.dpop.auth_password.internal

import com.example.dpop.auth_password.PASSWORD_ENROLLMENT_TYPE
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
     * [accountId]/[enrollmentRef] are null when the email is unknown, has no active password
     * method, or is currently throttled - a constant-shape failure in every case, so the response
     * never reveals whether the email exists (enumeration protection, docs/04-orchestrierung.md).
     *
     * **Constant shape means constant COST too.** The verification below deliberately runs
     * `PasswordHasher.matches` unconditionally rather than guarding it with `enrollment != null`:
     * Kotlin's `&&` short-circuits, so the guarded form skipped 210k PBKDF2 iterations for
     * unknown addresses and answered them in about a millisecond, which is trivially measurable
     * from outside. Keep the call unconditional when changing this - see PasswordHasher.matches.
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
            ?.takeIf { it.type == PASSWORD_ENROLLMENT_TYPE }
            ?.id?.toLongOrNull()
            ?.let { enrollmentRepository.findByIdOrNull(it) }

        // Unconditional, before any null check - a null enrollment hashes against a dummy and
        // costs the same. See the KDoc above.
        val passwordOk = PasswordHasher.matches(password!!, enrollment?.passwordHash)

        return if (accountId != null && enrollment != null && passwordOk) {
            ToolOutcome.Completed.Authenticated(
                amr = listOf(descriptor.method),
                achievedAcr = descriptor.maxAcr,
                factorTypes = descriptor.factorTypes,
                accountId = accountId
            )
        } else {
            // Naming the account here is what lets the orchestrator count this attempt; the
            // client-facing part of the outcome stays identical for known and unknown addresses.
            ToolOutcome.Failed("E-Mail oder Passwort ungueltig", attemptedAccountId = accountId)
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-password-lookup tool session: $toolSessionId" }
        return ToolOutcome.InProgress(nextStep = "auth", data = mapOf("missingFields" to listOf("email", "password"), demoData("email" to DEMO_EMAIL, "password" to DEMO_PASSWORD)))
    }
}
