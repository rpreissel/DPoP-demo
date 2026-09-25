package com.example.dpop.auth_sms.internal.enrollsms
import com.example.dpop.sms_mock.SmsGateway
import com.example.dpop.texts.Text
import com.example.dpop.auth_sms.internal.AuthSmsEnrollment
import com.example.dpop.auth_sms.internal.TanGenerator
import com.example.dpop.auth_sms.internal.AuthSmsEnrollmentRepository

import com.example.dpop.auth_sms.EnrollSmsDescriptor
import com.example.dpop.auth_sms.SMS_ENROLLMENT_TYPE
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.Claim
import com.example.dpop.tool_spi.ClaimSource
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * toolId=enroll-sms (docs/06-ablaeufe.md #4): registers a new phone number as a 2nd factor.
 * Pure business logic; self-description lives in [EnrollSmsDescriptor]. Its only
 * external caller is EnrollSmsToolController, which lives in the same module, so this class only
 * has to be visible within `auth_sms` - enforced by living under `internal` (docs/03-tool-architektur.md #2).
 *
 * Delegates the actual step logic to [EnrollSmsFlow]; this class only translates its [EnrollSmsDecision]
 * into persistence (`toolDataRepository`, `enrollmentRepository`) and the outward [ToolOutcome].
 */
@Component
class EnrollSmsToolHandler(
    private val descriptor: EnrollSmsDescriptor,
    private val toolDataRepository: EnrollSmsToolSessionRepository,
    private val enrollmentRepository: AuthSmsEnrollmentRepository,
    private val tanGenerator: TanGenerator,
    private val smsGateway: SmsGateway
) {

    /** Called directly by EnrollSmsToolController; nothing needs resolving before this can start. */
    @Transactional
    fun start(toolSessionId: UUID): ToolOutcome {
        toolDataRepository.save(EnrollSmsToolSession(toolSessionId = toolSessionId))
        return outcomeFor(EnrollSmsState.AwaitingPhoneNumber)
    }

    /**
     * Called directly by EnrollSmsToolController, not generically dispatched
     * (docs/08-projektrahmen.md A11).
     *
     * [sendThrottled] is resolved by the controller BEFORE this runs (auth_sms may not depend on
     * `orchestrator`, so it cannot ask the throttle itself) - see `ToolEndpoint.isSendThrottledForContact`.
     * It only matters when the decision turns out to be [EnrollSmsDecision.SendTan]: without it, an
     * attacker who merely knows a phone number could resubmit it to this tool indefinitely and use
     * it as a free SMS bomb, since resubmitting a number is never itself a wrong guess and so never
     * trips the ordinary throttle.
     */
    @Transactional
    fun patch(toolSessionId: UUID, phoneNumber: String?, tan: String?, sendThrottled: Boolean = false): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown enroll-sms tool session: $toolSessionId" }

        return when (val decision = EnrollSmsFlow.decide(data.toState(), EnrollSmsInput(phoneNumber, tan), tanGenerator)) {
            is EnrollSmsDecision.InvalidPhoneNumber -> throw IllegalArgumentException("Ungueltige Telefonnummer")

            is EnrollSmsDecision.WrongTan -> ToolOutcome.Failed(Text("TAN ungueltig oder abgelaufen"))

            is EnrollSmsDecision.Unchanged -> outcomeFor(decision.state)

            is EnrollSmsDecision.SendTan -> if (sendThrottled) {
                ToolOutcome.Failed(Text("Zu viele TAN-Anfragen fuer diese Nummer - bitte kurz warten"))
            } else {
                val issued = tanGenerator.issue()
                data.phoneNumber = decision.phoneNumber
                data.issuedTanHash = issued.hash
                data.tanExpiresAt = issued.expiresAt
                toolDataRepository.save(data)
                smsGateway.sendTan(decision.phoneNumber, issued.plainTan)

                val state = EnrollSmsState.AwaitingTan(decision.phoneNumber, issued.hash, issued.expiresAt)
                val (step, fields) = state.describe()
                // demoTan: this is a demo, not a real SMS gateway - showing it in the UI means
                // testers don't need server-log access (docs/06-ablaeufe.md #4).
                ToolOutcome.InProgress(nextStep = step, stepData = fields, demo = mapOf("tan" to issued.plainTan))
            }

            is EnrollSmsDecision.Complete -> {
                val enrollment = enrollmentRepository.save(AuthSmsEnrollment(decision.phoneNumber))
                ToolOutcome.Completed.Enrolled(
                    enrollmentRef = EnrollmentRef(type = SMS_ENROLLMENT_TYPE, id = enrollment.id.toString()),
                    amr = listOf(descriptor.method),
                    achievedAcr = descriptor.maxAcr,
                    factorTypes = descriptor.factorTypes,
                    claims = listOf(
                        Claim(
                            attributeType = AttributeType.PHONE_NUMBER,
                            value = decision.phoneNumber,
                            source = ClaimSource.of(descriptor.toolId),
                            establishedAcr = descriptor.maxAcr
                        )
                    ),
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
        return ToolOutcome.InProgress(nextStep = step, stepData = fields)
    }

    private fun EnrollSmsToolSession.toState(): EnrollSmsState = EnrollSmsState.of(
        toolSessionId = checkNotNull(toolSessionId),
        phoneNumber = phoneNumber,
        issuedTanHash = issuedTanHash,
        tanExpiresAt = tanExpiresAt
    )
}
