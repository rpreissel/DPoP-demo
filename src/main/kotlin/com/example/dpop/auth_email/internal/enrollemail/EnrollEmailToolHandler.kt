package com.example.dpop.auth_email.internal.enrollemail
import com.example.dpop.auth_email.internal.EmailCodeGenerator

import com.example.dpop.auth_email.EnrollEmailDescriptor
import com.example.dpop.tool_api.AccountDirectory
import com.example.dpop.tool_spi.CONFIRMED_EMAIL_AUDIT_KEY
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.ToolOutcome
import com.example.dpop.tool_spi.demoData
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * toolId=enroll-email: registers a confirmed email address as a knowledge factor,
 * mirroring enroll-sms (docs/06-ablaeufe.md #4) - a mock confirmation code stands in for a real
 * mail send, exactly like the mock SMS gateway.
 *
 * Unlike enroll-sms/enroll-password, the confirmed value is NOT stored in a module-owned
 * enrollment table referenced via EnrollmentRef - it lives directly on Account (deliberate
 * exception, same treatment as Account.personId: a single canonical account attribute, not a
 * swappable per-enrollment credential). [enrollmentRef] returned here is therefore a fixed,
 * inert placeholder.
 *
 * This handler no longer writes that value onto `Account` itself - it hands the confirmed address
 * through in `Completed.Enrolled.auditDetails` (same idiom `enroll-device`'s `deviceBindingKeyRef`/
 * `enroll-sms`'s `providerMsgId` already use), and `JourneyService`'s generic `Action.
 * AdoptCredential` handling calls `AccountService.confirmEmail` from there, gated by
 * `ToolDescriptor.confirmsAccountEmail`. This is what lets REGISTER "Enrollment zuerst"
 * (docs/04-orchestrierung.md) create the account lazily, on first `AdoptCredential`, instead of
 * needing one to already exist before this tool's own PATCH can even run - this was the ONE enroll
 * handler in the whole catalog that needed an account mid-PATCH; every other one already operates
 * purely on its own tool-session data.
 *
 * Pure business logic; self-description lives in [EnrollEmailDescriptor] (DPoP-demo-vun).
 * Delegates the actual step logic to [EnrollEmailFlow]; this class only translates its
 * [EnrollEmailDecision] into persistence writes and the outward [ToolOutcome].
 */
@Component
class EnrollEmailToolHandler(
    private val descriptor: EnrollEmailDescriptor,
    private val toolDataRepository: EnrollEmailToolDataRepository,
    private val accountDirectory: AccountDirectory,
    private val emailCodeGenerator: EmailCodeGenerator
) {

    /** Called directly by EnrollEmailToolController; nothing needs resolving before this can start. */
    @Transactional
    fun start(toolSessionId: UUID): ToolOutcome {
        toolDataRepository.save(EnrollEmailToolData(toolSessionId = toolSessionId))
        return outcomeFor(EnrollEmailState.AwaitingEmail)
    }

    /** Called directly by EnrollEmailToolController, not generically dispatched (docs/08-projektrahmen.md A11). */
    @Transactional
    fun patch(toolSessionId: UUID, email: String?, code: String?): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown enroll-email tool session: $toolSessionId" }

        return when (val decision = EnrollEmailFlow.decide(data.toState(), EnrollEmailInput(email, code), emailCodeGenerator)) {
            is EnrollEmailDecision.InvalidEmail -> throw IllegalArgumentException("Ungueltige E-Mail-Adresse")

            is EnrollEmailDecision.WrongCode -> ToolOutcome.Failed("Code ungueltig oder abgelaufen")

            is EnrollEmailDecision.Unchanged -> outcomeFor(decision.state)

            is EnrollEmailDecision.RequestCode -> {
                // Queried directly rather than handed in pre-resolved by the controller - only a
                // yes/no uniqueness check, so the narrow `tool_api.AccountDirectory` port (every
                // other method module's own account access) is enough; no `account` dependency needed.
                if (accountDirectory.resolveAccountByEmail(decision.email) != null) {
                    ToolOutcome.Failed("E-Mail-Adresse bereits vergeben")
                } else {
                    val issued = emailCodeGenerator.issue()
                    data.email = decision.email
                    data.issuedCodeHash = issued.hash
                    data.codeExpiresAt = issued.expiresAt
                    toolDataRepository.save(data)
                    sendMockEmail(decision.email, issued.plainCode)

                    val state = EnrollEmailState.AwaitingCode(decision.email, issued.hash, issued.expiresAt)
                    val (step, fields) = state.describe()
                    // demoTan: reuses the existing demo-value plumbing (docs/05-api.md #2's `demo`
                    // object) - this is a demo, not a real mail gateway, and a second field for
                    // "the other kind of demo code" would be unnecessary special-casing.
                    ToolOutcome.InProgress(nextStep = step, data = fields + demoData("tan" to issued.plainCode))
                }
            }

            is EnrollEmailDecision.Complete -> ToolOutcome.Completed.Enrolled(
                enrollmentRef = EnrollmentRef(type = "account_email", id = "self"),
                amr = listOf(descriptor.method),
                achievedAcr = descriptor.maxAcr,
                factorTypes = descriptor.factorTypes,
                // JourneyService's Action.AdoptCredential handling confirms this onto Account
                // itself (ToolDescriptor.confirmsAccountEmail) - see class doc for why this
                // handler no longer writes it directly.
                auditDetails = mapOf(CONFIRMED_EMAIL_AUDIT_KEY to decision.email)
            )
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown enroll-email tool session: $toolSessionId" }
        return outcomeFor(data.toState())
    }

    private fun outcomeFor(state: EnrollEmailState): ToolOutcome.InProgress {
        val (step, fields) = state.describe()
        return ToolOutcome.InProgress(nextStep = step, data = fields)
    }

    private fun EnrollEmailToolData.toState(): EnrollEmailState = EnrollEmailState.of(
        toolSessionId = checkNotNull(toolSessionId),
        email = email,
        issuedCodeHash = issuedCodeHash,
        codeExpiresAt = codeExpiresAt
    )

    private fun sendMockEmail(email: String, code: String) {
        println("[MOCK EMAIL] Code $code an $email versandt (enroll-email).")
    }
}
