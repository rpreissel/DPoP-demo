package com.example.dpop.auth_email

import com.example.dpop.auth_email.internal.AuthEmailLookupToolData
import com.example.dpop.auth_email.internal.AuthEmailLookupToolDataRepository
import com.example.dpop.auth_email.internal.EmailCodeGenerator
import com.example.dpop.tool_spi.DEMO_EMAIL
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.ToolCategory
import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ToolOutcome
import com.example.dpop.tool_spi.demoData
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * toolId=auth-email-lookup: "login ohne DPoP" (docs/04-orchestrierung.md) - proves possession of
 * the account's confirmed email address without the account already being known via the
 * channel. Unlike [AuthEmailUseToolHandler], this module cannot resolve the account itself
 * (auth_email may only depend on tool_spi, docs/08-projektrahmen.md A11) - the controller
 * resolves [submitEmail]'s [accountId]/[confirmedEmail] from the submitted email via
 * AccountService, mirroring AuthSmsLookupToolHandler.
 */
@Component
class AuthEmailLookupToolHandler(
    private val toolDataRepository: AuthEmailLookupToolDataRepository
) : ToolDescriptor {

    override val toolId = "auth-email-lookup"
    override val category = ToolCategory.AUTH
    override val method = "email"
    override val factorTypes = setOf(FactorType.POSSESSION)
    override val maxAcr = "loa1"
    override val deviceBound = false

    @Transactional
    fun start(toolSessionId: UUID): ToolOutcome {
        toolDataRepository.save(AuthEmailLookupToolData(toolSessionId = toolSessionId))
        return ToolOutcome.InProgress(nextStep = "auth", data = mapOf("missingFields" to listOf("email"), demoData("email" to DEMO_EMAIL)))
    }

    /**
     * [accountId]/[confirmedEmail] are null when the submitted email is unknown or not (yet)
     * confirmed on any account - handled identically to a resolved account with an (inevitably)
     * wrong code afterwards, so the response shape never reveals whether the email exists
     * (enumeration protection, docs/04-orchestrierung.md).
     */
    @Transactional
    fun submitEmail(toolSessionId: UUID, accountId: Long?, confirmedEmail: String?): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-email-lookup tool session: $toolSessionId" }

        val issued = EmailCodeGenerator.issue()
        data.accountId = accountId.takeIf { confirmedEmail != null }
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
                amr = listOf("email"),
                achievedAcr = maxAcr,
                factorTypes = factorTypes,
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
