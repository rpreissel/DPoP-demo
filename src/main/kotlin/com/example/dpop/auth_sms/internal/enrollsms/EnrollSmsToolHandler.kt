package com.example.dpop.auth_sms.internal.enrollsms
import com.example.dpop.auth_sms.internal.AuthSmsEnrollment
import com.example.dpop.auth_sms.internal.TanGenerator
import com.example.dpop.auth_sms.internal.AuthSmsEnrollmentRepository

import com.example.dpop.auth_sms.EnrollSmsDescriptor
import com.example.dpop.auth_sms.SMS_ENROLLMENT_TYPE
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
 *
 * Delegates the actual step logic to [EnrollSmsFlow]; this class only translates its [EnrollSmsDecision]
 * into persistence (`toolDataRepository`, `enrollmentRepository`) and the outward [ToolOutcome].
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
        return outcomeFor(EnrollSmsState.AwaitingPhoneNumber)
    }

    /** Called directly by EnrollSmsToolController, not generically dispatched (docs/08-projektrahmen.md A11). */
    @Transactional
    fun patch(toolSessionId: UUID, phoneNumber: String?, tan: String?): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown enroll-sms tool session: $toolSessionId" }

        return when (val decision = EnrollSmsFlow.decide(data.toState(), EnrollSmsInput(phoneNumber, tan), tanGenerator)) {
            is EnrollSmsDecision.InvalidPhoneNumber -> throw IllegalArgumentException("Ungueltige Telefonnummer")

            is EnrollSmsDecision.WrongTan -> ToolOutcome.Failed("TAN ungueltig oder abgelaufen")

            is EnrollSmsDecision.Unchanged -> outcomeFor(decision.state)

            is EnrollSmsDecision.SendTan -> {
                val issued = tanGenerator.issue()
                data.phoneNumber = decision.phoneNumber
                data.issuedTanHash = issued.hash
                data.tanExpiresAt = issued.expiresAt
                toolDataRepository.save(data)
                sendMockSms(decision.phoneNumber, issued.plainTan)

                val state = EnrollSmsState.AwaitingTan(decision.phoneNumber, issued.hash, issued.expiresAt)
                val (step, fields) = state.describe()
                // demoTan: this is a demo, not a real SMS gateway - showing it in the UI means
                // testers don't need server-log access (docs/06-ablaeufe.md #4).
                ToolOutcome.InProgress(nextStep = step, data = fields + demoData("tan" to issued.plainTan))
            }

            is EnrollSmsDecision.Complete -> {
                val enrollment = enrollmentRepository.save(AuthSmsEnrollment(decision.phoneNumber))
                ToolOutcome.Completed.Enrolled(
                    enrollmentRef = EnrollmentRef(type = SMS_ENROLLMENT_TYPE, id = enrollment.id.toString()),
                    amr = listOf(descriptor.method),
                    achievedAcr = descriptor.maxAcr,
                    factorTypes = descriptor.factorTypes,
                    auditDetails = mapOf("smsProvider" to "sms-gw", "providerMsgId" to "MSG-$toolSessionId")
                )
            }
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown enroll-sms tool session: $toolSessionId" }
        return outcomeFor(data.toState())
    }

    private fun outcomeFor(state: EnrollSmsState): ToolOutcome.InProgress {
        val (step, fields) = state.describe()
        return ToolOutcome.InProgress(nextStep = step, data = fields)
    }

    private fun EnrollSmsToolData.toState(): EnrollSmsState = EnrollSmsState.of(
        toolSessionId = checkNotNull(toolSessionId),
        phoneNumber = phoneNumber,
        issuedTanHash = issuedTanHash,
        tanExpiresAt = tanExpiresAt
    )

    private fun sendMockSms(phoneNumber: String, tan: String) {
        println("[MOCK SMS] TAN $tan an $phoneNumber versandt (enroll-sms).")
    }
}
