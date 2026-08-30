package com.example.dpop.auth_sms.internal.authsmslookup
import com.example.dpop.auth_sms.internal.TanGenerator
import com.example.dpop.auth_sms.internal.AuthSmsEnrollmentRepository

import com.example.dpop.auth_sms.AuthSmsLookupDescriptor
import com.example.dpop.auth_sms.SMS_ENROLLMENT_TYPE
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.ToolOutcome
import com.example.dpop.tool_spi.demoData
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * toolId=auth-sms-lookup: "login ohne DPoP" (docs/04-orchestrierung.md) - proves possession of
 * an enrolled phone number without the account already being known via the channel. Unlike
 * AuthSmsUseToolHandler, this module cannot resolve the account itself (auth_sms may only
 * depend on tool_spi, docs/08-projektrahmen.md A11) - the controller resolves [email] to an
 * accountId/EnrollmentRef via AccountService and passes the result into [submitEmail].
 *
 * Pure business logic; self-description lives in [AuthSmsLookupDescriptor] (DPoP-demo-vun).
 * Delegates the tan-vs-state decision to [AuthSmsLookupFlow].
 */
@Component
class AuthSmsLookupToolHandler(
    private val descriptor: AuthSmsLookupDescriptor,
    private val toolDataRepository: AuthSmsLookupToolDataRepository,
    private val enrollmentRepository: AuthSmsEnrollmentRepository,
    private val tanGenerator: TanGenerator
) {

    @Transactional
    fun start(toolSessionId: UUID): ToolOutcome {
        toolDataRepository.save(AuthSmsLookupToolData(toolSessionId = toolSessionId))
        return outcomeFor(AuthSmsLookupState.AwaitingEmail)
    }

    /**
     * [accountId]/[enrollmentRef] are null when the email is unknown, has no active sms method,
     * or is currently throttled - handled identically to a resolved account with an (inevitably)
     * wrong TAN afterwards, so the response shape never reveals whether the email exists
     * (enumeration protection, docs/04-orchestrierung.md).
     *
     * The throttled case folding into "no enrollment" also stops this endpoint from being an
     * SMS pump: no resolution, no send.
     */
    @Transactional
    fun submitEmail(toolSessionId: UUID, accountId: Long?, enrollmentRef: EnrollmentRef?): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-sms-lookup tool session: $toolSessionId" }

        val enrollment = enrollmentRef
            ?.takeIf { it.type == SMS_ENROLLMENT_TYPE }
            ?.id?.toLongOrNull()
            ?.let { enrollmentRepository.findByIdOrNull(it) }
        val resolvedAccountId = accountId.takeIf { enrollment != null }

        val issued = tanGenerator.issue()
        data.accountId = resolvedAccountId
        data.issuedTanHash = issued.hash
        data.tanExpiresAt = issued.expiresAt
        toolDataRepository.save(data)

        val state = AuthSmsLookupState.AwaitingTan(resolvedAccountId, issued.hash, issued.expiresAt)
        val (step, fields) = state.describe()
        // Only actually "send" (and reveal a demoTan for) an SMS when the email really resolved
        // to an account with an active sms method - otherwise there is nothing to send to.
        return if (enrollment != null) {
            sendMockSms(enrollment.phoneNumber.orEmpty(), issued.plainTan)
            ToolOutcome.InProgress(nextStep = step, data = fields + demoData("tan" to issued.plainTan))
        } else {
            ToolOutcome.InProgress(nextStep = step, data = fields)
        }
    }

    /** Called directly by AuthSmsLookupToolController, not generically dispatched (docs/08-projektrahmen.md A11). */
    @Transactional
    fun patch(toolSessionId: UUID, tan: String?): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-sms-lookup tool session: $toolSessionId" }

        return when (val decision = AuthSmsLookupFlow.decideTan(data.toState(), tan, tanGenerator)) {
            is AuthSmsLookupDecision.Unchanged -> outcomeFor(decision.state)

            is AuthSmsLookupDecision.Complete -> ToolOutcome.Completed.Authenticated(
                amr = listOf(descriptor.method),
                achievedAcr = descriptor.maxAcr,
                factorTypes = descriptor.factorTypes,
                accountId = decision.accountId
            )

            is AuthSmsLookupDecision.WrongTan ->
                // accountId names the throttle subject for the orchestrator; it is null exactly
                // when nothing resolved, so there is nothing to count either.
                ToolOutcome.Failed("E-Mail oder TAN ungueltig", attemptedAccountId = decision.accountId)
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-sms-lookup tool session: $toolSessionId" }
        return outcomeFor(data.toState())
    }

    private fun outcomeFor(state: AuthSmsLookupState): ToolOutcome.InProgress {
        val (step, fields) = state.describe()
        return ToolOutcome.InProgress(nextStep = step, data = fields)
    }

    private fun AuthSmsLookupToolData.toState(): AuthSmsLookupState = AuthSmsLookupState.of(
        toolSessionId = checkNotNull(toolSessionId),
        accountId = accountId,
        issuedTanHash = issuedTanHash,
        tanExpiresAt = tanExpiresAt
    )

    private fun sendMockSms(phoneNumber: String, tan: String) {
        println("[MOCK SMS] TAN $tan an $phoneNumber versandt (auth-sms-lookup).")
    }
}
