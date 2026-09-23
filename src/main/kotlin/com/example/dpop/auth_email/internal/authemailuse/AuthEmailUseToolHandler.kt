package com.example.dpop.auth_email.internal.authemailuse
import com.example.dpop.auth_email.internal.EmailCodeGenerator

import com.example.dpop.auth_email.AuthEmailUseDescriptor
import com.example.dpop.tool_api.AccountDirectory
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.ToolOutcome
import com.example.dpop.tool_spi.UnresolvableReferenceException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * toolId=auth-email (device-linked case only - see docs/03-tool-architektur.md).
 *
 * No EnrollmentRef involved: the confirmed address is the account's canonical email attribute,
 * so [start] reads it through the generic `anchorValue` port. Where AuthSmsUseToolHandler
 * resolves an EnrollmentRef into its own enrollment row, this tool resolves an accountId into
 * the account's address - the same shape, against the anchor projection that actually holds
 * this credential. No `account` dependency: auth_email hangs on tool_spi/tool_api alone like
 * every other method module.
 *
 * Pure business logic; self-description lives in [AuthEmailUseDescriptor].
 * Delegates the code-vs-state decision to [AuthEmailUseFlow].
 */
@Component
class AuthEmailUseToolHandler(
    private val descriptor: AuthEmailUseDescriptor,
    private val toolDataRepository: AuthEmailUseToolSessionRepository,
    private val accountDirectory: AccountDirectory,
    private val emailCodeGenerator: EmailCodeGenerator
) {

    /**
     * Resolves the account's confirmed address itself and fails with the same
     * [UnresolvableReferenceException] its siblings raise for an unresolvable EnrollmentRef
     * (-> 422). auth-email has no EnrollmentRef to resolve - the address IS the credential and
     * lives on Account - so this lookup is the exact analogue, and doing it here rather than in
     * the controller makes the tool consistent with auth-sms/auth-password/auth-device.
     */
    @Transactional
    fun start(toolSessionId: UUID, accountId: Long): ToolOutcome {
        // Returns the normalized confirmed address from the anchor projection, or null when
        // none was ever established for this account.
        val email = accountDirectory.anchorValue(accountId, AttributeType.EMAIL)
            ?: throw UnresolvableReferenceException("Keine bestaetigte E-Mail-Adresse fuer diesen Account")

        val issued = emailCodeGenerator.issue()
        toolDataRepository.save(
            AuthEmailUseToolSession(toolSessionId = toolSessionId, issuedCodeHash = issued.hash, codeExpiresAt = issued.expiresAt)
        )
        sendMockEmail(email, issued.plainCode)

        val (step, fields) = AuthEmailUseState(issued.hash, issued.expiresAt).describe()
        return ToolOutcome.InProgress(nextStep = step, stepData = fields, demo = mapOf("tan" to issued.plainCode))
    }

    /** Called directly by AuthEmailToolController, not generically dispatched (docs/08-projektrahmen.md A11). */
    @Transactional
    fun patch(toolSessionId: UUID, code: String?): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-email tool session: $toolSessionId" }
        val state = data.toState()

        return when (AuthEmailUseFlow.decide(state, AuthEmailUseInput(code), emailCodeGenerator)) {
            AuthEmailUseDecision.Unchanged -> outcomeFor(state)
            AuthEmailUseDecision.WrongCode -> ToolOutcome.Failed("Code ungueltig oder abgelaufen")
            AuthEmailUseDecision.Complete -> ToolOutcome.Completed.Authenticated(
                amr = listOf(descriptor.method),
                achievedAcr = descriptor.maxAcr,
                factorTypes = descriptor.factorTypes
            )
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-email tool session: $toolSessionId" }
        return outcomeFor(data.toState())
    }

    private fun outcomeFor(state: AuthEmailUseState): ToolOutcome.InProgress {
        val (step, fields) = state.describe()
        return ToolOutcome.InProgress(nextStep = step, stepData = fields)
    }

    private fun AuthEmailUseToolSession.toState(): AuthEmailUseState = AuthEmailUseState.of(
        toolSessionId = checkNotNull(toolSessionId),
        issuedCodeHash = issuedCodeHash,
        codeExpiresAt = codeExpiresAt
    )

    private fun sendMockEmail(email: String, code: String) {
        println("[MOCK EMAIL] Code $code an $email versandt (auth-email).")
    }
}
