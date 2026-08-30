package com.example.dpop.auth_email.internal.authemaillookup
import com.example.dpop.auth_email.internal.EmailCodeGenerator

import com.example.dpop.account.AccountService
import com.example.dpop.auth_email.AuthEmailLookupDescriptor
import com.example.dpop.tool_spi.ToolOutcome
import com.example.dpop.tool_spi.demoData
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * toolId=auth-email-lookup: "login ohne DPoP" (docs/04-orchestrierung.md) - proves possession of
 * the account's confirmed email address without the account already being known via the
 * channel. [submitEmail] resolves the account from the submitted address itself, via the declared
 * `auth_email -> account` dependency (see ModuleMetadata) - unlike AuthSmsLookupToolHandler,
 * which still receives a pre-resolved accountId because it only needs an opaque account handle,
 * not the email semantics this module owns.
 *
 * Pure business logic; self-description lives in [AuthEmailLookupDescriptor] (DPoP-demo-vun).
 * Delegates the code-vs-state decision to [AuthEmailLookupFlow].
 */
@Component
class AuthEmailLookupToolHandler(
    private val descriptor: AuthEmailLookupDescriptor,
    private val toolDataRepository: AuthEmailLookupToolDataRepository,
    private val accountService: AccountService,
    private val emailCodeGenerator: EmailCodeGenerator
) {

    @Transactional
    fun start(toolSessionId: UUID): ToolOutcome {
        toolDataRepository.save(AuthEmailLookupToolData(toolSessionId = toolSessionId))
        return outcomeFor(AuthEmailLookupState.AwaitingEmail)
    }

    /**
     * Resolves [email] against the account store itself (declared `auth_email -> account`
     * dependency, see ModuleMetadata).
     *
     * **Enumeration protection** (docs/04-orchestrierung.md): an unknown or not-yet-confirmed
     * address must be indistinguishable from a known one. Both branches below therefore issue a
     * code, persist the same shape and return the same `codeInput` step - only the actual send is
     * skipped when there is no address to send to, and the stored accountId stays null so the
     * later code check inevitably fails.
     *
     * [throttled] joins that same branch rather than getting an error of its own: the caller has
     * already found this account locked out, and a distinguishable answer here would hand back
     * the account-existence oracle everything above is built to deny. It also means no mail goes
     * out, so a locked account cannot be mail-bombed through this endpoint.
     */
    @Transactional
    fun submitEmail(toolSessionId: UUID, email: String, throttled: Boolean): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-email-lookup tool session: $toolSessionId" }

        val account = accountService.findAccountByEmail(email).takeUnless { throttled }
        val confirmedEmail = account?.takeIf { it.emailConfirmed }?.email
        val resolvedAccountId = account?.accountId.takeIf { confirmedEmail != null }

        val issued = emailCodeGenerator.issue()
        data.accountId = resolvedAccountId
        data.issuedCodeHash = issued.hash
        data.codeExpiresAt = issued.expiresAt
        toolDataRepository.save(data)

        val state = AuthEmailLookupState.AwaitingCode(resolvedAccountId, issued.hash, issued.expiresAt)
        val (step, fields) = state.describe()
        // Only actually "send" (and reveal a demo code for) an email when it really resolved to
        // a confirmed account address - otherwise there is nothing to send to.
        return if (confirmedEmail != null) {
            sendMockEmail(confirmedEmail, issued.plainCode)
            ToolOutcome.InProgress(nextStep = step, data = fields + demoData("tan" to issued.plainCode))
        } else {
            ToolOutcome.InProgress(nextStep = step, data = fields)
        }
    }

    /** Called directly by AuthEmailLookupToolController, not generically dispatched (docs/08-projektrahmen.md A11). */
    @Transactional
    fun patch(toolSessionId: UUID, code: String?): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-email-lookup tool session: $toolSessionId" }

        return when (val decision = AuthEmailLookupFlow.decideCode(data.toState(), code, emailCodeGenerator)) {
            is AuthEmailLookupDecision.Unchanged -> outcomeFor(decision.state)

            is AuthEmailLookupDecision.Complete -> ToolOutcome.Completed.Authenticated(
                amr = listOf(descriptor.method),
                achievedAcr = descriptor.maxAcr,
                factorTypes = descriptor.factorTypes,
                accountId = decision.accountId
            )

            is AuthEmailLookupDecision.WrongCode ->
                // accountId names the throttle subject for the orchestrator; it is null exactly
                // when nothing resolved, so there is nothing to count either.
                ToolOutcome.Failed("E-Mail oder Code ungueltig", attemptedAccountId = decision.accountId)
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-email-lookup tool session: $toolSessionId" }
        return outcomeFor(data.toState())
    }

    private fun outcomeFor(state: AuthEmailLookupState): ToolOutcome.InProgress {
        val (step, fields) = state.describe()
        return ToolOutcome.InProgress(nextStep = step, data = fields)
    }

    private fun AuthEmailLookupToolData.toState(): AuthEmailLookupState = AuthEmailLookupState.of(
        toolSessionId = checkNotNull(toolSessionId),
        accountId = accountId,
        issuedCodeHash = issuedCodeHash,
        codeExpiresAt = codeExpiresAt
    )

    private fun sendMockEmail(email: String, code: String) {
        println("[MOCK EMAIL] Code $code an $email versandt (auth-email-lookup).")
    }
}
