package com.example.dpop.auth_sms.internal.authsmsuse
import com.example.dpop.auth_sms.internal.TanGenerator
import com.example.dpop.auth_sms.internal.AuthSmsEnrollmentRepository

import com.example.dpop.auth_sms.AuthSmsUseDescriptor
import com.example.dpop.auth_sms.SMS_ENROLLMENT_TYPE
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.ToolOutcome
import com.example.dpop.tool_spi.UnresolvableReferenceException
import com.example.dpop.tool_spi.demoData
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * toolId=auth-sms (docs/06-ablaeufe.md #3). [start]'s [enrollmentRef] must be the account's
 * active SMS enrollment reference - resolved and null-checked by AuthSmsToolController before
 * calling this (never null here), since this module never reads `account` itself.
 *
 * Pure business logic; self-description lives in [AuthSmsUseDescriptor] (DPoP-demo-vun). Its
 * only external caller is AuthSmsToolController, which lives in the same module.
 * Delegates the tan-vs-state decision to [AuthSmsUseFlow].
 */
@Component
class AuthSmsUseToolHandler(
    private val descriptor: AuthSmsUseDescriptor,
    private val toolDataRepository: AuthSmsUseToolDataRepository,
    private val enrollmentRepository: AuthSmsEnrollmentRepository,
    private val tanGenerator: TanGenerator
) {

    @Transactional
    fun start(toolSessionId: UUID, enrollmentRef: EnrollmentRef): ToolOutcome {
        if (enrollmentRef.type != SMS_ENROLLMENT_TYPE) {
            throw UnresolvableReferenceException("Unerwarteter Enrollment-Typ: ${enrollmentRef.type}")
        }
        val enrollmentId = enrollmentRef.id.toLongOrNull()
            ?: throw UnresolvableReferenceException("Ungueltige Enrollment-Referenz: ${enrollmentRef.id}")
        val enrollment = enrollmentRepository.findByIdOrNull(enrollmentId)
            ?: throw UnresolvableReferenceException("SMS-Enrollment nicht gefunden: ${enrollmentRef.id}")

        val issued = tanGenerator.issue()
        toolDataRepository.save(
            AuthSmsUseToolData(
                toolSessionId = toolSessionId,
                enrollmentRefType = enrollmentRef.type,
                enrollmentRefId = enrollmentRef.id,
                issuedTanHash = issued.hash,
                tanExpiresAt = issued.expiresAt
            )
        )
        sendMockSms(enrollment.phoneNumber.orEmpty(), issued.plainTan)

        // demoTan: this is a demo, not a real SMS gateway - showing it in the UI means testers
        // don't need server-log access (docs/06-ablaeufe.md #3).
        val (step, fields) = AuthSmsUseState(issued.hash, issued.expiresAt).describe()
        return ToolOutcome.InProgress(nextStep = step, data = fields + demoData("tan" to issued.plainTan))
    }

    /** Called directly by AuthSmsToolController, not generically dispatched (docs/08-projektrahmen.md A11). */
    @Transactional
    fun patch(toolSessionId: UUID, tan: String?): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-sms tool session: $toolSessionId" }
        val state = data.toState()

        return when (AuthSmsUseFlow.decide(state, AuthSmsUseInput(tan), tanGenerator)) {
            AuthSmsUseDecision.Unchanged -> outcomeFor(state)
            AuthSmsUseDecision.WrongTan -> ToolOutcome.Failed("TAN ungueltig oder abgelaufen")
            AuthSmsUseDecision.Complete -> ToolOutcome.Completed.Authenticated(
                amr = listOf(descriptor.method),
                achievedAcr = descriptor.maxAcr,
                factorTypes = descriptor.factorTypes
            )
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-sms tool session: $toolSessionId" }
        return outcomeFor(data.toState())
    }

    private fun outcomeFor(state: AuthSmsUseState): ToolOutcome.InProgress {
        val (step, fields) = state.describe()
        return ToolOutcome.InProgress(nextStep = step, data = fields)
    }

    private fun AuthSmsUseToolData.toState(): AuthSmsUseState = AuthSmsUseState.of(
        toolSessionId = checkNotNull(toolSessionId),
        issuedTanHash = issuedTanHash,
        tanExpiresAt = tanExpiresAt
    )

    private fun sendMockSms(phoneNumber: String, tan: String) {
        println("[MOCK SMS] TAN $tan an $phoneNumber versandt (auth-sms).")
    }
}
