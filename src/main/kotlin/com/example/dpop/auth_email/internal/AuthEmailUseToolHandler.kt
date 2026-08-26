package com.example.dpop.auth_email.internal

import com.example.dpop.account.AccountService
import com.example.dpop.auth_email.AuthEmailUseDescriptor
import com.example.dpop.tool_spi.ToolOutcome
import com.example.dpop.tool_spi.UnresolvableReferenceException
import com.example.dpop.tool_spi.demoData
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * toolId=auth-email (device-linked case only for now - see docs/03-tool-architektur.md).
 *
 * No EnrollmentRef involved: the confirmed address lives directly on Account, so [start] reads it
 * from there itself via the declared `auth_email -> account` dependency (see ModuleMetadata).
 * Where AuthSmsUseToolHandler resolves an EnrollmentRef into its own enrollment row, this tool
 * resolves an accountId into the account's address - the same shape, against the store that
 * actually holds this credential.
 *
 * Pure business logic; self-description lives in [AuthEmailUseDescriptor] (DPoP-demo-vun).
 */
@Component
class AuthEmailUseToolHandler(
    private val descriptor: AuthEmailUseDescriptor,
    private val toolDataRepository: AuthEmailUseToolDataRepository,
    private val accountService: AccountService
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
        val account = accountService.findAccount(accountId)
        val email = account?.takeIf { it.emailConfirmed }?.email
            ?: throw UnresolvableReferenceException("Keine bestaetigte E-Mail-Adresse fuer diesen Account")

        val issued = EmailCodeGenerator.issue()
        toolDataRepository.save(
            AuthEmailUseToolData(toolSessionId = toolSessionId, issuedCodeHash = issued.hash, codeExpiresAt = issued.expiresAt)
        )
        sendMockEmail(email, issued.plainCode)

        return ToolOutcome.InProgress(nextStep = "auth", data = mapOf("missingFields" to listOf("code"), demoData("tan" to issued.plainCode)))
    }

    /** Called directly by AuthEmailToolController, not generically dispatched (docs/08-projektrahmen.md A11). */
    @Transactional
    fun patch(toolSessionId: UUID, code: String?): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-email tool session: $toolSessionId" }

        val codeValue = code
            ?: return ToolOutcome.InProgress(nextStep = "auth", data = mapOf("missingFields" to listOf("code")))

        return if (EmailCodeGenerator.matches(codeValue, data.issuedCodeHash, data.codeExpiresAt)) {
            ToolOutcome.Completed.Authenticated(
                amr = listOf("email"),
                achievedAcr = descriptor.maxAcr,
                factorTypes = descriptor.factorTypes
            )
        } else {
            ToolOutcome.Failed("Code ungueltig oder abgelaufen")
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-email tool session: $toolSessionId" }
        return ToolOutcome.InProgress(nextStep = "auth", data = mapOf("missingFields" to listOf("code")))
    }

    private fun sendMockEmail(email: String, code: String) {
        println("[MOCK EMAIL] Code $code an $email versandt (auth-email).")
    }
}
