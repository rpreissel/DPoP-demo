package com.example.dpop.auth_sms

import com.example.dpop.auth_sms.internal.AuthSmsEnrollment
import com.example.dpop.auth_sms.internal.AuthSmsEnrollmentRepository
import com.example.dpop.auth_sms.internal.EnrollSmsToolData
import com.example.dpop.auth_sms.internal.EnrollSmsToolDataRepository
import com.example.dpop.auth_sms.internal.TanGenerator
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
 * toolId=enroll-sms (docs/06-ablaeufe.md #4): registers a new phone number as a 2nd factor.
 * Implements ToolDescriptor directly rather than through a wrapper interface
 * (docs/03-tool-architektur.md #2) - ToolHandlerRegistry collects `List<ToolDescriptor>`
 * straight from Spring.
 */
@Component
class EnrollSmsToolHandler(
    private val toolDataRepository: EnrollSmsToolDataRepository,
    private val enrollmentRepository: AuthSmsEnrollmentRepository
) : ToolDescriptor {

    override val toolId = "enroll-sms"
    override val role = MethodRole.ENROLLMENT
    override val methodFamily = SMS_METHOD
    override val factorTypes = setOf(FactorType.POSSESSION)
    override val maxAcr = "loa1"

    /** Called directly by EnrollSmsToolController; nothing needs resolving before this can start. */
    @Transactional
    fun start(toolSessionId: UUID): ToolOutcome {
        toolDataRepository.save(EnrollSmsToolData(toolSessionId = toolSessionId))
        return ToolOutcome.InProgress(nextStep = "enroll", data = mapOf("missingFields" to listOf("phoneNumber")))
    }

    /** Called directly by EnrollSmsToolController, not generically dispatched (docs/08-projektrahmen.md A11). */
    @Transactional
    fun patch(toolSessionId: UUID, phoneNumber: String?, tan: String?): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown enroll-sms tool session: $toolSessionId" }

        if (tan != null) {
            if (data.phoneNumber == null) {
                return ToolOutcome.InProgress(nextStep = "enroll", data = mapOf("missingFields" to listOf("phoneNumber")))
            }
            if (!TanGenerator.matches(tan, data.issuedTanHash, data.tanExpiresAt)) {
                return ToolOutcome.Failed("TAN ungueltig oder abgelaufen")
            }
            val enrollment = enrollmentRepository.save(AuthSmsEnrollment(data.phoneNumber))
            return ToolOutcome.Completed.Enrolled(
                enrollmentRef = EnrollmentRef(type = "auth_sms_enrollment", id = enrollment.id.toString()),
                amr = listOf("sms"),
                achievedAcr = maxAcr,
                factorTypes = factorTypes,
                auditDetails = mapOf("smsProvider" to "sms-gw", "providerMsgId" to "MSG-$toolSessionId")
            )
        }

        val number = phoneNumber
            ?: return ToolOutcome.InProgress(nextStep = "enroll", data = mapOf("missingFields" to listOf("phoneNumber")))
        val normalized = normalizePhoneNumber(number)
        if (!PHONE_PATTERN.matches(normalized)) {
            throw IllegalArgumentException("Ungueltige Telefonnummer")
        }

        val issued = TanGenerator.issue()
        data.phoneNumber = normalized
        data.issuedTanHash = issued.hash
        data.tanExpiresAt = issued.expiresAt
        toolDataRepository.save(data)
        sendMockSms(normalized, issued.plainTan)

        // demoTan: this is a demo, not a real SMS gateway - showing it in the UI means testers
        // don't need server-log access (docs/06-ablaeufe.md #4).
        return ToolOutcome.InProgress(nextStep = "tanInput", data = mapOf("missingFields" to listOf("tan"), demoData("tan" to issued.plainTan)))
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown enroll-sms tool session: $toolSessionId" }
        return if (data.phoneNumber == null) {
            ToolOutcome.InProgress(nextStep = "enroll", data = mapOf("missingFields" to listOf("phoneNumber")))
        } else {
            ToolOutcome.InProgress(nextStep = "tanInput", data = mapOf("missingFields" to listOf("tan")))
        }
    }

    private fun normalizePhoneNumber(phoneNumber: String): String =
        phoneNumber.replace("\\s+".toRegex(), "").trim()

    private fun sendMockSms(phoneNumber: String, tan: String) {
        println("[MOCK SMS] TAN $tan an $phoneNumber versandt (enroll-sms).")
    }

    companion object {
        private val PHONE_PATTERN = "^\\+?[0-9]{6,20}$".toRegex()
    }
}
