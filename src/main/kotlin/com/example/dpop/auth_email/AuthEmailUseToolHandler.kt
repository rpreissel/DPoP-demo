package com.example.dpop.auth_email

import com.example.dpop.auth_email.internal.AuthEmailUseToolData
import com.example.dpop.auth_email.internal.AuthEmailUseToolDataRepository
import com.example.dpop.auth_email.internal.EmailCodeGenerator
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
 * toolId=auth-email (device-linked case only for now - see docs/03-tool-architektur.md).
 * [start]'s [email] is the account's own confirmed address, resolved and null-checked by
 * AuthEmailToolController before calling this (never null here) - this module never reads
 * `account` itself, matching AuthSmsUseToolHandler's pattern. No EnrollmentRef involved: the
 * email lives directly on Account, so there is nothing to resolve beyond the string itself.
 */
@Component
class AuthEmailUseToolHandler(
    private val toolDataRepository: AuthEmailUseToolDataRepository
) : ToolDescriptor {

    override val toolId = "auth-email"
    override val category = ToolCategory.AUTH
    override val method = "email"
    override val factorTypes = setOf(FactorType.POSSESSION)
    override val maxAcr = "loa1"

    @Transactional
    fun start(toolSessionId: UUID, email: String): ToolOutcome {
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
                achievedAcr = maxAcr,
                factorTypes = factorTypes
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
