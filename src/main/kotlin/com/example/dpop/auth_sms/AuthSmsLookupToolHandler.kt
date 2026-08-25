package com.example.dpop.auth_sms

import com.example.dpop.auth_sms.internal.AuthSmsEnrollmentRepository
import com.example.dpop.auth_sms.internal.AuthSmsLookupToolData
import com.example.dpop.auth_sms.internal.AuthSmsLookupToolDataRepository
import com.example.dpop.auth_sms.internal.TanGenerator
import com.example.dpop.tool_spi.DEMO_EMAIL
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ToolOutcome
import com.example.dpop.tool_spi.demoData
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * toolId=auth-sms-lookup: "login ohne DPoP" (docs/04-orchestrierung.md) - proves possession of
 * an enrolled phone number without the account already being known via the channel. Unlike
 * [AuthSmsUseToolHandler], this module cannot resolve the account itself (auth_sms may only
 * depend on tool_spi, docs/08-projektrahmen.md A11) - the controller resolves [email] to an
 * accountId/EnrollmentRef via AccountService and passes the result into [submitEmail].
 */
@Component
class AuthSmsLookupToolHandler(
    private val toolDataRepository: AuthSmsLookupToolDataRepository,
    private val enrollmentRepository: AuthSmsEnrollmentRepository
) : ToolDescriptor {

    override val toolId = "auth-sms-lookup"
    override val role = MethodRole.LOOKUP_AUTH
    override val methodFamily = SMS_METHOD
    override val factorTypes = setOf(FactorType.POSSESSION)
    override val maxAcr = "loa1"

    @Transactional
    fun start(toolSessionId: UUID): ToolOutcome {
        toolDataRepository.save(AuthSmsLookupToolData(toolSessionId = toolSessionId))
        return ToolOutcome.InProgress(nextStep = "auth", data = mapOf("missingFields" to listOf("email"), demoData("email" to DEMO_EMAIL)))
    }

    /**
     * [accountId]/[enrollmentRef] are null when the email is unknown or has no active sms
     * method - handled identically to a resolved account with an (inevitably) wrong TAN
     * afterwards, so the response shape never reveals whether the email exists (enumeration
     * protection, docs/04-orchestrierung.md).
     */
    @Transactional
    fun submitEmail(toolSessionId: UUID, accountId: Long?, enrollmentRef: EnrollmentRef?): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-sms-lookup tool session: $toolSessionId" }

        val enrollment = enrollmentRef
            ?.takeIf { it.type == "auth_sms_enrollment" }
            ?.id?.toLongOrNull()
            ?.let { enrollmentRepository.findByIdOrNull(it) }

        val issued = TanGenerator.issue()
        data.accountId = accountId.takeIf { enrollment != null }
        data.issuedTanHash = issued.hash
        data.tanExpiresAt = issued.expiresAt
        toolDataRepository.save(data)

        // Only actually "send" (and reveal a demoTan for) an SMS when the email really resolved
        // to an account with an active sms method - otherwise there is nothing to send to.
        // nextStep="tanInput", distinct from the "auth" step of the email stage - same naming as
        // enroll-sms, needed so the frontend routing table (keyed by toolId+step) can tell the
        // two stages apart (docs/10-frontend.md).
        return if (enrollment != null) {
            sendMockSms(enrollment.phoneNumber.orEmpty(), issued.plainTan)
            ToolOutcome.InProgress(nextStep = "tanInput", data = mapOf("missingFields" to listOf("tan"), demoData("tan" to issued.plainTan)))
        } else {
            ToolOutcome.InProgress(nextStep = "tanInput", data = mapOf("missingFields" to listOf("tan")))
        }
    }

    /** Called directly by AuthSmsLookupToolController, not generically dispatched (docs/08-projektrahmen.md A11). */
    @Transactional
    fun patch(toolSessionId: UUID, tan: String?): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-sms-lookup tool session: $toolSessionId" }

        if (tan == null) {
            return if (data.issuedTanHash == null) {
                ToolOutcome.InProgress(nextStep = "auth", data = mapOf("missingFields" to listOf("email"), demoData("email" to DEMO_EMAIL)))
            } else {
                ToolOutcome.InProgress(nextStep = "tanInput", data = mapOf("missingFields" to listOf("tan")))
            }
        }

        val accountId = data.accountId
        return if (accountId != null && TanGenerator.matches(tan, data.issuedTanHash, data.tanExpiresAt)) {
            ToolOutcome.Completed.Authenticated(
                amr = listOf("sms"),
                achievedAcr = maxAcr,
                factorTypes = factorTypes,
                accountId = accountId
            )
        } else {
            ToolOutcome.Failed("E-Mail oder TAN ungueltig")
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-sms-lookup tool session: $toolSessionId" }
        return if (data.issuedTanHash == null) {
            ToolOutcome.InProgress(nextStep = "auth", data = mapOf("missingFields" to listOf("email")))
        } else {
            ToolOutcome.InProgress(nextStep = "tanInput", data = mapOf("missingFields" to listOf("tan")))
        }
    }

    private fun sendMockSms(phoneNumber: String, tan: String) {
        println("[MOCK SMS] TAN $tan an $phoneNumber versandt (auth-sms-lookup).")
    }
}
