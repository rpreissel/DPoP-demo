package com.example.dpop.auth_email.internal.enrollemail
import com.example.dpop.auth_email.internal.EmailCodeGenerator

import com.example.dpop.auth_email.EnrollEmailDescriptor
import com.example.dpop.tool_api.AccountDirectory
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.Claim
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.ToolOutcome
import com.example.dpop.tool_spi.ClaimSource
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
 * enrollment table referenced via EnrollmentRef - it is the account's canonical email attribute,
 * the first anchor-role application of the claims model (docs/ideen/
 * claims-modell-und-vertrauensanker.md). It is therefore asserted as a typed EMAIL claim on
 * `Completed.Enrolled`, which `JourneyService`'s generic `Action.AdoptCredential` handling
 * records via `AccountService.recordClaim` - consolidating the projection column, the anchor
 * and the AccountChanged event. This is what lets REGISTER "Enrollment zuerst"
 * (docs/04-orchestrierung.md) create the account lazily, on first `AdoptCredential`, instead of
 * needing one to already exist before this tool's own PATCH can even run - this was the ONE enroll
 * handler in the whole catalog that needed an account mid-PATCH; every other one already operates
 * purely on its own tool-session data.
 *
 * Pure business logic; self-description lives in [EnrollEmailDescriptor].
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

    /**
     * Called directly by EnrollEmailToolController, not generically dispatched
     * (docs/08-projektrahmen.md A11).
     *
     * [sendThrottled] is resolved by the controller BEFORE this runs (auth_email may not depend on
     * `orchestrator`, so it cannot ask the throttle itself) - see `ToolEndpoint.isSendThrottledForContact`.
     * It only matters when the decision turns out to be [EnrollEmailDecision.RequestCode]: without
     * it, an attacker who merely knows an email address could resubmit it to this tool indefinitely
     * and use it as a free mail bomb, since resubmitting an address is never itself a wrong guess
     * and so never trips the ordinary throttle.
     */
    @Transactional
    fun patch(toolSessionId: UUID, email: String?, code: String?, sendThrottled: Boolean = false): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown enroll-email tool session: $toolSessionId" }

        return when (val decision = EnrollEmailFlow.decide(data.toState(), EnrollEmailInput(email, code), emailCodeGenerator)) {
            is EnrollEmailDecision.InvalidEmail -> throw IllegalArgumentException("Ungueltige E-Mail-Adresse")

            is EnrollEmailDecision.WrongCode -> ToolOutcome.Failed("Code ungueltig oder abgelaufen")

            is EnrollEmailDecision.Unchanged -> outcomeFor(decision.state)

            is EnrollEmailDecision.RequestCode -> {
                // Queried through the generic anchor port - the same normalized lookup the write
                // side uses, so "already taken" can't be raced past via case tricks; only a
                // yes/no uniqueness check, so the narrow `tool_api.AccountDirectory` port
                // (every other method module's own account access) is enough.
                if (accountDirectory.resolveByAnchor(AttributeType.EMAIL, decision.email) != null) {
                    ToolOutcome.Failed("E-Mail-Adresse bereits vergeben")
                } else if (sendThrottled) {
                    ToolOutcome.Failed("Zu viele Anfragen fuer diese E-Mail-Adresse - bitte kurz warten")
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
                // The anchor IS the durable reference here: it binds this method instance to the
                // actual confirmed value instead of an inert placeholder, so the singleton
                // idempotency check in `addAuthenticationMethod` matches on the real thing.
                enrollmentRef = EnrollmentRef(type = "email", id = decision.email),
                amr = listOf(descriptor.method),
                achievedAcr = descriptor.maxAcr,
                factorTypes = descriptor.factorTypes,
                claims = listOf(
                    // This enrollment asserts a proven email. The code exchange itself IS the
                    // proof, hence this tool's own id as the trust anchor.
                    Claim(AttributeType.EMAIL, decision.email, ClaimSource.of(descriptor.toolId), descriptor.maxAcr)
                )
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
