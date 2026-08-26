package com.example.dpop.auth_email.internal

import com.example.dpop.account.AccountService
import com.example.dpop.auth_email.AuthEmailLookupDescriptor
import com.example.dpop.tool_spi.DEMO_EMAIL
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
 */
@Component
class AuthEmailLookupToolHandler(
    private val descriptor: AuthEmailLookupDescriptor,
    private val toolDataRepository: AuthEmailLookupToolDataRepository,
    private val accountService: AccountService
) {

    @Transactional
    fun start(toolSessionId: UUID): ToolOutcome {
        toolDataRepository.save(AuthEmailLookupToolData(toolSessionId = toolSessionId))
        return ToolOutcome.InProgress(nextStep = "auth", data = mapOf("missingFields" to listOf("email"), demoData("email" to DEMO_EMAIL)))
    }

    /**
     * Resolves [email] against the account store itself (declared `auth_email -> account`
     * dependency, see ModuleMetadata).
     *
     * **Enumeration protection** (docs/04-orchestrierung.md): an unknown or not-yet-confirmed
     * address must be indistinguishable from a known one. Both branches below therefore issue a
     * code, persist the same shape and return the same `codeInput` step - only the actual send is
     * skipped when there is no address to send to, and the stored accountId stays null so the
     * later code check inevitably fails. Keep both paths structurally identical when changing
     * this; a difference in response, timing or step name would leak account existence.
     */
    @Transactional
    fun submitEmail(toolSessionId: UUID, email: String): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-email-lookup tool session: $toolSessionId" }

        val account = accountService.findAccountByEmail(email)
        val confirmedEmail = account?.takeIf { it.emailConfirmed }?.email

        val issued = EmailCodeGenerator.issue()
        data.accountId = account?.accountId.takeIf { confirmedEmail != null }
        data.issuedCodeHash = issued.hash
        data.codeExpiresAt = issued.expiresAt
        toolDataRepository.save(data)

        // Only actually "send" (and reveal a demo code for) an email when it really resolved to
        // a confirmed account address - otherwise there is nothing to send to. nextStep matches
        // enroll-email/auth-email's codeInput step so the frontend routing table can reuse the
        // same form (docs/10-frontend.md).
        return if (confirmedEmail != null) {
            sendMockEmail(confirmedEmail, issued.plainCode)
            ToolOutcome.InProgress(nextStep = "codeInput", data = mapOf("missingFields" to listOf("code"), demoData("tan" to issued.plainCode)))
        } else {
            ToolOutcome.InProgress(nextStep = "codeInput", data = mapOf("missingFields" to listOf("code")))
        }
    }

    /** Called directly by AuthEmailLookupToolController, not generically dispatched (docs/08-projektrahmen.md A11). */
    @Transactional
    fun patch(toolSessionId: UUID, code: String?): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-email-lookup tool session: $toolSessionId" }

        if (code == null) {
            return if (data.issuedCodeHash == null) {
                ToolOutcome.InProgress(nextStep = "auth", data = mapOf("missingFields" to listOf("email"), demoData("email" to DEMO_EMAIL)))
            } else {
                ToolOutcome.InProgress(nextStep = "codeInput", data = mapOf("missingFields" to listOf("code")))
            }
        }

        val accountId = data.accountId
        return if (accountId != null && EmailCodeGenerator.matches(code, data.issuedCodeHash, data.codeExpiresAt)) {
            ToolOutcome.Completed.Authenticated(
                amr = listOf(descriptor.method),
                achievedAcr = descriptor.maxAcr,
                factorTypes = descriptor.factorTypes,
                accountId = accountId
            )
        } else {
            ToolOutcome.Failed("E-Mail oder Code ungueltig")
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-email-lookup tool session: $toolSessionId" }
        return if (data.issuedCodeHash == null) {
            ToolOutcome.InProgress(nextStep = "auth", data = mapOf("missingFields" to listOf("email")))
        } else {
            ToolOutcome.InProgress(nextStep = "codeInput", data = mapOf("missingFields" to listOf("code")))
        }
    }

    private fun sendMockEmail(email: String, code: String) {
        println("[MOCK EMAIL] Code $code an $email versandt (auth-email-lookup).")
    }
}
