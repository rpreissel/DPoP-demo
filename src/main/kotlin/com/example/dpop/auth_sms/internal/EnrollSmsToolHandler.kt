package com.example.dpop.auth_sms.internal

import com.example.dpop.auth_sms.EnrollSmsDescriptor
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.ToolOutcome
import com.example.dpop.tool_spi.demoData
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * toolId=enroll-sms (docs/06-ablaeufe.md #4): registers a new phone number as a 2nd factor.
 * Pure business logic; self-description lives in [EnrollSmsDescriptor] (DPoP-demo-vun). Its only
 * external caller is EnrollSmsToolController, which lives in the same module, so this class only
 * has to be visible within `auth_sms` - enforced by living under `internal` (docs/03-tool-architektur.md #2).
 */
@Component
class EnrollSmsToolHandler(
    private val descriptor: EnrollSmsDescriptor,
    private val toolDataRepository: EnrollSmsToolDataRepository,
    private val enrollmentRepository: AuthSmsEnrollmentRepository,
    private val tanGenerator: TanGenerator
) {

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
            if (!tanGenerator.matches(tan, data.issuedTanHash, data.tanExpiresAt)) {
                return ToolOutcome.Failed("TAN ungueltig oder abgelaufen")
            }
            val enrollment = enrollmentRepository.save(AuthSmsEnrollment(data.phoneNumber))
            return ToolOutcome.Completed.Enrolled(
                enrollmentRef = EnrollmentRef(type = "auth_sms_enrollment", id = enrollment.id.toString()),
                amr = listOf(descriptor.method),
                achievedAcr = descriptor.maxAcr,
                factorTypes = descriptor.factorTypes,
                auditDetails = mapOf("smsProvider" to "sms-gw", "providerMsgId" to "MSG-$toolSessionId")
            )
        }

        val number = phoneNumber
            ?: return ToolOutcome.InProgress(nextStep = "enroll", data = mapOf("missingFields" to listOf("phoneNumber")))
        val normalized = normalizePhoneNumber(number)
        if (!PHONE_PATTERN.matches(normalized)) {
            throw IllegalArgumentException("Ungueltige Telefonnummer")
        }

        val issued = tanGenerator.issue()
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
