package com.example.dpop.auth_email

import com.example.dpop.account.AccountService
import com.example.dpop.auth_email.internal.EmailCodeGenerator
import com.example.dpop.auth_email.internal.EnrollEmailToolData
import com.example.dpop.auth_email.internal.EnrollEmailToolDataRepository
import com.example.dpop.tool_spi.DEMO_EMAIL
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
 * toolId=enroll-email: registers a confirmed email address as a knowledge/possession factor,
 * mirroring enroll-sms (docs/06-ablaeufe.md #4) - a mock confirmation code stands in for a real
 * mail send, exactly like the mock SMS gateway.
 *
 * Unlike enroll-sms/enroll-password, the confirmed value is NOT stored in a module-owned
 * enrollment table referenced via EnrollmentRef - it lives directly on Account (deliberate
 * exception, same treatment as Account.personId: a single canonical account attribute, not a
 * swappable per-enrollment credential). [enrollmentRef] returned here is therefore a fixed,
 * inert placeholder.
 *
 * This module writes that value onto Account **itself**, via the declared `auth_email -> account`
 * dependency (see [ModuleMetadata] for why this one module is exempt). Previously the generic
 * [com.example.dpop.orchestrator.orchestration.ToolOutcomeProcessor] did it behind an
 * `if (method == "email")` branch, fed by an `"email"` key smuggled through `auditDetails` - the
 * coupling existed either way, it was just invisible and sat in the one layer that is supposed to
 * treat all methods alike. The email deliberately does NOT travel in `auditDetails` any more: it
 * must not be duplicated into the generic `authenticationMethods[].details` blob.
 */
@Component
class EnrollEmailToolHandler(
    private val toolDataRepository: EnrollEmailToolDataRepository,
    private val accountService: AccountService
) : ToolDescriptor {

    override val toolId = "enroll-email"
    override val role = MethodRole.ENROLLMENT
    override val methodFamily = EMAIL_METHOD
    override val factorTypes = setOf(FactorType.POSSESSION)
    override val maxAcr = "loa1"
    override val confirmsAccountEmail = true

    /** Called directly by EnrollEmailToolController; nothing needs resolving before this can start. */
    @Transactional
    fun start(toolSessionId: UUID): ToolOutcome {
        toolDataRepository.save(EnrollEmailToolData(toolSessionId = toolSessionId))
        return ToolOutcome.InProgress(nextStep = "enroll", data = mapOf("missingFields" to listOf("email"), demoData("email" to DEMO_EMAIL)))
    }

    /**
     * Called directly by EnrollEmailToolController, not generically dispatched
     * (docs/08-projektrahmen.md A11). [accountId] is the account the enrolling process is bound
     * to - needed because the confirmed address is written onto it right here.
     */
    @Transactional
    fun patch(toolSessionId: UUID, email: String?, code: String?, accountId: Long): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown enroll-email tool session: $toolSessionId" }

        if (code != null) {
            val confirmed = data.email
                ?: return ToolOutcome.InProgress(nextStep = "enroll", data = mapOf("missingFields" to listOf("email"), demoData("email" to DEMO_EMAIL)))
            if (!EmailCodeGenerator.matches(code, data.issuedCodeHash, data.codeExpiresAt)) {
                return ToolOutcome.Failed("Code ungueltig oder abgelaufen")
            }
            // Must happen before the Enrolled outcome is processed, not after: the orchestrator's
            // resolveNext re-reads the account to evaluate ConfirmedEmailRequiredAction, so a
            // later write would leave REGISTRATION looping on enroll-email forever.
            accountService.confirmEmail(accountId, confirmed)
            return ToolOutcome.Completed.Enrolled(
                enrollmentRef = EnrollmentRef(type = "account_email", id = "self"),
                amr = listOf("email"),
                achievedAcr = maxAcr,
                factorTypes = factorTypes
            )
        }

        val value = email
            ?: return ToolOutcome.InProgress(nextStep = "enroll", data = mapOf("missingFields" to listOf("email"), demoData("email" to DEMO_EMAIL)))
        val normalized = value.trim().lowercase()
        if (!EMAIL_PATTERN.matches(normalized)) {
            throw IllegalArgumentException("Ungueltige E-Mail-Adresse")
        }
        // Queried directly rather than handed in pre-resolved by the controller - the declared
        // `account` dependency makes the indirection pointless ceremony (see [ModuleMetadata]).
        if (accountService.existsByEmail(normalized)) {
            return ToolOutcome.Failed("E-Mail-Adresse bereits vergeben")
        }

        val issued = EmailCodeGenerator.issue()
        data.email = normalized
        data.issuedCodeHash = issued.hash
        data.codeExpiresAt = issued.expiresAt
        toolDataRepository.save(data)
        sendMockEmail(normalized, issued.plainCode)

        // demoTan: reuses the existing demo-value plumbing (docs/05-api.md #2's `demo` object) -
        // this is a demo, not a real mail gateway, and a second field for "the other kind of
        // demo code" would be unnecessary special-casing of the same concept.
        return ToolOutcome.InProgress(nextStep = "codeInput", data = mapOf("missingFields" to listOf("code"), demoData("tan" to issued.plainCode)))
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown enroll-email tool session: $toolSessionId" }
        return if (data.email == null) {
            ToolOutcome.InProgress(nextStep = "enroll", data = mapOf("missingFields" to listOf("email"), demoData("email" to DEMO_EMAIL)))
        } else {
            ToolOutcome.InProgress(nextStep = "codeInput", data = mapOf("missingFields" to listOf("code")))
        }
    }

    private fun sendMockEmail(email: String, code: String) {
        println("[MOCK EMAIL] Code $code an $email versandt (enroll-email).")
    }

    companion object {
        private val EMAIL_PATTERN = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$".toRegex()
    }
}
