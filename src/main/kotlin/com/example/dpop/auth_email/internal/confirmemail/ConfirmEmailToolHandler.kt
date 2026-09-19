package com.example.dpop.auth_email.internal.confirmemail
import com.example.dpop.auth_email.internal.EmailCodeGenerator

import com.example.dpop.auth_email.ConfirmEmailDescriptor
import com.example.dpop.tool_api.AccountDirectory
import com.example.dpop.tool_api.EMAIL_ANCHOR_ENROLLMENT
import com.example.dpop.tool_api.resolveAccountByEmail
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.Claim
import com.example.dpop.tool_spi.ToolOutcome
import com.example.dpop.tool_spi.ClaimSource
import com.example.dpop.tool_spi.demoData
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * toolId=confirm-email, role=ATTESTATION: proves control of an address, mirroring enroll-sms's
 * code exchange (docs/06-ablaeufe.md #4) - a mock confirmation code stands in for a real mail
 * send, exactly like the mock SMS gateway.
 *
 * The confirmed value is NOT stored in a module-owned enrollment table - it is the account's
 * EMAIL anchor, referenced as [EMAIL_ANCHOR_ENROLLMENT], the first anchor-role application of the
 * claims model (docs/ideen/claims-modell-und-vertrauensanker.md). It is therefore asserted as a
 * typed EMAIL claim on `Completed.Attested`, which `JourneyActionExecutor.performAdoptAttestation`
 * records via `AccountService.recordClaims` - consolidating the anchor and firing AccountChanged,
 * but creating no method instance and no device binding (docs/03-tool-architektur.md "ATTEST").
 * This is what lets REGISTER "Enrollment zuerst" (docs/04-orchestrierung.md) create the account
 * lazily, on this very first step, instead of needing one to already exist beforehand - this was
 * the ONE handler in the whole catalog that needed an account mid-PATCH; every other one already
 * operates purely on its own tool-session data.
 *
 * Pure business logic; self-description lives in [ConfirmEmailDescriptor].
 * Delegates the actual step logic to [ConfirmEmailFlow]; this class only translates its
 * [ConfirmEmailDecision] into persistence writes and the outward [ToolOutcome].
 */
@Component
class ConfirmEmailToolHandler(
    private val descriptor: ConfirmEmailDescriptor,
    private val toolDataRepository: ConfirmEmailToolSessionRepository,
    private val accountDirectory: AccountDirectory,
    private val emailCodeGenerator: EmailCodeGenerator
) {

    /** Called directly by ConfirmEmailToolController; nothing needs resolving before this can start. */
    @Transactional
    fun start(toolSessionId: UUID): ToolOutcome {
        toolDataRepository.save(ConfirmEmailToolSession(toolSessionId = toolSessionId))
        return outcomeFor(ConfirmEmailState.AwaitingEmail)
    }

    /**
     * Called directly by ConfirmEmailToolController, not generically dispatched
     * (docs/08-projektrahmen.md A11).
     *
     * [sendThrottled] is resolved by the controller BEFORE this runs (auth_email may not depend on
     * `orchestrator`, so it cannot ask the throttle itself) - see `ToolEndpoint.isSendThrottledForContact`.
     * It only matters when the decision turns out to be [ConfirmEmailDecision.RequestCode]: without
     * it, an attacker who merely knows an email address could resubmit it to this tool indefinitely
     * and use it as a free mail bomb, since resubmitting an address is never itself a wrong guess
     * and so never trips the ordinary throttle.
     */
    @Transactional
    fun patch(toolSessionId: UUID, email: String?, code: String?, sendThrottled: Boolean = false): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown confirm-email tool session: $toolSessionId" }

        return when (val decision = ConfirmEmailFlow.decide(data.toState(), ConfirmEmailInput(email, code), emailCodeGenerator)) {
            is ConfirmEmailDecision.InvalidEmail -> throw IllegalArgumentException("Ungueltige E-Mail-Adresse")

            is ConfirmEmailDecision.WrongCode -> ToolOutcome.Failed("Code ungueltig oder abgelaufen")

            is ConfirmEmailDecision.Unchanged -> outcomeFor(decision.state)

            is ConfirmEmailDecision.RequestCode -> {
                // Queried through the generic anchor port - the same normalized lookup the write
                // side uses, so "already taken" can't be raced past via case tricks; only a
                // yes/no uniqueness check, so the narrow `tool_api.AccountDirectory` port
                // (every other method module's own account access) is enough.
                if (accountDirectory.resolveAccountByEmail(decision.email) != null) {
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

                    val state = ConfirmEmailState.AwaitingCode(decision.email, issued.hash, issued.expiresAt)
                    val (step, fields) = state.describe()
                    // demoTan: reuses the existing demo-value plumbing (docs/05-api.md #2's `demo`
                    // object) - this is a demo, not a real mail gateway, and a second field for
                    // "the other kind of demo code" would be unnecessary special-casing.
                    ToolOutcome.InProgress(nextStep = step, data = fields + demoData("tan" to issued.plainCode))
                }
            }

            is ConfirmEmailDecision.Complete -> ToolOutcome.Completed.Attested(
                claims = listOf(
                    // The code exchange itself IS the proof, hence this tool's own id as the
                    // trust anchor. No enrollmentRef and no amr: this run established a fact about
                    // the account, it did not authenticate anyone (ToolOutcome.Completed.Attested).
                    Claim(AttributeType.EMAIL, decision.email, ClaimSource.of(descriptor.toolId), descriptor.maxAcr)
                ),
                achievedAcr = descriptor.maxAcr
            )
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown confirm-email tool session: $toolSessionId" }
        return outcomeFor(data.toState())
    }

    private fun outcomeFor(state: ConfirmEmailState): ToolOutcome.InProgress {
        val (step, fields) = state.describe()
        return ToolOutcome.InProgress(nextStep = step, data = fields)
    }

    private fun ConfirmEmailToolSession.toState(): ConfirmEmailState = ConfirmEmailState.of(
        toolSessionId = checkNotNull(toolSessionId),
        email = email,
        issuedCodeHash = issuedCodeHash,
        codeExpiresAt = codeExpiresAt
    )

    private fun sendMockEmail(email: String, code: String) {
        println("[MOCK EMAIL] Code $code an $email versandt (confirm-email).")
    }
}
