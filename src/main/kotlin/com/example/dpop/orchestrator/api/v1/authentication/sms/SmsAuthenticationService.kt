package com.example.dpop.orchestrator.api.v1.authentication.sms

import com.example.dpop.account.AccountService
import com.example.dpop.auth_sms.AuthSmsService
import com.example.dpop.auth_sms.EnrollmentRef
import com.example.dpop.orchestrator.api.v1.AttemptPendingStore
import com.example.dpop.orchestrator.api.v1.OrchestratorException
import com.example.dpop.orchestrator.api.v1.OrchestratorResponse
import com.example.dpop.orchestrator.api.v1.load
import com.example.dpop.orchestrator.session.AttemptStatus
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.orchestrator.session.LoginProcessSession
import com.example.dpop.orchestrator.session.OrchestratorAttempt
import com.example.dpop.orchestrator.session.ProcessPurpose
import com.example.dpop.orchestrator.session.ProcessSession
import com.example.dpop.orchestrator.session.SessionManagementService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.util.UUID

@Service
@Transactional
class SmsAuthenticationService(
    private val sessionManagementService: SessionManagementService,
    private val accountService: AccountService,
    private val authSmsService: AuthSmsService,
    private val pendingStore: AttemptPendingStore
) {

    fun startEnroll(channelSessionId: UUID, bindingKeyRef: String): OrchestratorResponse {
        val channelSession = getChannelSession(bindingKeyRef, channelSessionId)
        val processSession = getOrCreateLoginSession(channelSession)
        val processSessionId = processSession.processSessionId
            ?: throw IllegalStateException("Process session has no id")
        val attempt = sessionManagementService.createAuthenticationAttempt(
            processSessionId, "sms", "enroll", Duration.ofMinutes(10)
        )
        return advanceEnroll(attempt, SmsEnrollPending.empty(), channelSession)
    }

    fun submitEnroll(attemptId: UUID, bindingKeyRef: String, patchData: Map<String, Any>?): OrchestratorResponse {
        val attempt = sessionManagementService.findAttemptById(attemptId)
            ?: throw OrchestratorException.notFound("Attempt not found")
        if (attempt.status == AttemptStatus.VERIFIED) {
            throw OrchestratorException.conflict("Attempt is already verified")
        }
        val channelSession = getChannelSession(bindingKeyRef, null)
        val pending = pendingStore.load<SmsEnrollPending>(attempt)
            ?.merge(patchData)
            ?: SmsEnrollPending.empty().merge(patchData)
        return advanceEnroll(attempt, pending, channelSession)
    }

    fun startUse(channelSessionId: UUID, bindingKeyRef: String): OrchestratorResponse {
        val channelSession = getChannelSession(bindingKeyRef, channelSessionId)
        val accountId = channelSession.accountId
            ?: throw OrchestratorException.forbidden("No account bound to channel")

        val enrollmentId = accountService.findActiveSmsEnrollmentId(accountId)
            ?: throw IllegalStateException("No active SMS enrollment found")
        val ref = EnrollmentRef(enrollmentId)

        val challenge = authSmsService.startChallenge(ref)

        val processSession = getOrCreateLoginSession(channelSession)
        val processSessionId = processSession.processSessionId
            ?: throw IllegalStateException("Process session has no id")
        val attempt = sessionManagementService.createAuthenticationAttempt(
            processSessionId, "sms", "use", Duration.ofMinutes(10)
        )
        val attemptId = attempt.attemptId ?: throw IllegalStateException("Attempt has no id")
        val channelId = channelSession.channelSessionId
            ?: throw IllegalStateException("Channel session has no id")

        val pending = SmsUsePending(ref, null)
        pendingStore.save(attempt, pending)
        attempt.status = AttemptStatus.INPUT_REQUIRED
        saveMissingFields(attempt, listOf("tan"))
        sessionManagementService.updateAttempt(attempt)

        return OrchestratorResponse(
            channelSessionId = channelId,
            attemptState = OrchestratorResponse.AttemptState(
                attemptId = attemptId,
                attemptType = "authentication",
                status = "INPUT_REQUIRED",
                missingFields = listOf("tan"),
                result = mapOf("enrollmentId" to ref.id)
            ),
            next = OrchestratorResponse.NextRouting(
                context = "sms",
                step = "use"
            ),
            _demo = OrchestratorResponse.DemoHints(
                tan = challenge.tan,
                note = "DEMO ONLY – in production the TAN is sent via SMS and never included in the API response"
            )
        )
    }

    fun submitUse(attemptId: UUID, bindingKeyRef: String, patchData: Map<String, Any>?): OrchestratorResponse {
        val attempt = sessionManagementService.findAttemptById(attemptId)
            ?: throw OrchestratorException.notFound("Attempt not found")
        if (attempt.status == AttemptStatus.VERIFIED) {
            throw OrchestratorException.conflict("Attempt is already verified")
        }
        val channelSession = getChannelSession(bindingKeyRef, null)
        val pending = pendingStore.load<SmsUsePending>(attempt)
            ?: throw IllegalStateException("No pending use data found")
        val merged = pending.merge(patchData)
        return advanceUse(attempt, merged, channelSession)
    }

    fun getStatus(attemptId: UUID, bindingKeyRef: String): OrchestratorResponse {
        val attempt = sessionManagementService.findAttemptById(attemptId)
            ?: throw OrchestratorException.notFound("Attempt not found")
        val result = attempt.result
            ?.takeIf { attempt.status == AttemptStatus.VERIFIED }
        return OrchestratorResponse(
            channelSessionId = null,
            attemptState = OrchestratorResponse.AttemptState(
                attemptId = attempt.attemptId,
                attemptType = "authentication",
                status = attempt.status.toString(),
                missingFields = null,
                result = result
            ),
            next = OrchestratorResponse.NextRouting(
                context = attempt.nextContext,
                step = attempt.nextStep
            )
        )
    }

    private fun nextEnrollStep(p: SmsEnrollPending): EnrollStep = when {
        p.phoneNumber.isNullOrBlank() -> EnrollStep.NeedInput(p.missingUserInputs())
        p.enrollmentRef == null -> EnrollStep.StartEnrollment(p.phoneNumber)
        p.tan.isNullOrBlank() -> EnrollStep.NeedInput(listOf("tan"))
        !p.tanVerified -> EnrollStep.ConfirmEnrollment(p.enrollmentRef, p.tan)
        !p.enrollmentConfirmed -> EnrollStep.ActivateMethod(p.enrollmentRef)
        else -> throw IllegalStateException("Enroll bereits abgeschlossen")
    }

    private fun advanceEnroll(
        attempt: OrchestratorAttempt,
        pending: SmsEnrollPending,
        channel: ChannelSession
    ): OrchestratorResponse = when (val step = nextEnrollStep(pending)) {
        is EnrollStep.NeedInput -> {
            val missing = step.missingFields
            val attemptId = attempt.attemptId ?: throw IllegalStateException("Attempt has no id")
            val channelId = channel.channelSessionId ?: throw IllegalStateException("Channel has no id")
            pendingStore.save(attempt, pending)
            saveMissingFields(attempt, missing)
            attempt.status = AttemptStatus.INPUT_REQUIRED
            sessionManagementService.updateAttempt(attempt)
            val hints = if (missing.contains("tan") && pending.enrollmentRef != null) {
                OrchestratorResponse.DemoHints(
                    tan = pending.tan,
                    note = "DEMO ONLY – in production the TAN is sent via SMS and never included in the API response"
                )
            } else null
            OrchestratorResponse(
                channelSessionId = channelId,
                attemptState = OrchestratorResponse.AttemptState(
                    attemptId = attemptId,
                    attemptType = "authentication",
                    status = "INPUT_REQUIRED",
                    missingFields = missing,
                    result = null
                ),
                next = OrchestratorResponse.NextRouting(
                    context = "sms",
                    step = "enroll"
                ),
                _demo = hints
            )
        }
        is EnrollStep.StartEnrollment -> {
            val result = authSmsService.startEnrollment(step.phoneNumber)
            val attemptId = attempt.attemptId ?: throw IllegalStateException("Attempt has no id")
            val channelId = channel.channelSessionId ?: throw IllegalStateException("Channel has no id")
            val withRef = SmsEnrollPending(
                phoneNumber = step.phoneNumber,
                enrollmentRef = result.enrollmentRef,
                tan = null,
                tanVerified = false,
                enrollmentConfirmed = false
            )
            pendingStore.save(attempt, withRef)
            saveMissingFields(attempt, listOf("tan"))
            attempt.status = AttemptStatus.INPUT_REQUIRED
            sessionManagementService.updateAttempt(attempt)
            OrchestratorResponse(
                channelSessionId = channelId,
                attemptState = OrchestratorResponse.AttemptState(
                    attemptId = attemptId,
                    attemptType = "authentication",
                    status = "INPUT_REQUIRED",
                    missingFields = listOf("tan"),
                    result = null
                ),
                next = OrchestratorResponse.NextRouting(
                    context = "sms",
                    step = "enroll"
                ),
                _demo = OrchestratorResponse.DemoHints(
                    tan = result.tan,
                    note = "DEMO ONLY – in production the TAN is sent via SMS and never included in the API response"
                )
            )
        }
        is EnrollStep.ConfirmEnrollment -> {
            try {
                authSmsService.confirmEnrollment(step.ref, step.tan)
            } catch (e: Exception) {
                throw OrchestratorException.verificationFailed("TAN validation failed")
            }
            advanceEnroll(attempt, pending.withTanVerified(), channel)
        }
        is EnrollStep.ActivateMethod -> {
            val accountId = channel.accountId ?: throw IllegalStateException("Channel has no account id")
            val attemptId = attempt.attemptId ?: throw IllegalStateException("Attempt has no id")
            val channelId = channel.channelSessionId ?: throw IllegalStateException("Channel has no id")
            val enrollmentId = step.ref.id
                ?: throw IllegalStateException("EnrollmentRef has no id")
            accountService.addAuthenticationMethod(
                accountId, "sms", true, mapOf("enrollmentId" to enrollmentId)
            )
            attempt.status = AttemptStatus.VERIFIED
            attempt.result = "{ \"verified\": true }"
            sessionManagementService.updateAttempt(attempt)
            sessionManagementService.updateChannelState(channelId, ChannelState.AUTHENTICATED)
            val processSessionId = attempt.processSessionId
                ?: throw IllegalStateException("Attempt has no process session id")
            val personId = sessionManagementService.findProcessSessionById(processSessionId)?.personId
            OrchestratorResponse(
                channelSessionId = channelId,
                processState = OrchestratorResponse.ProcessState(
                    purpose = "REGISTRATION",
                    status = "COMPLETED",
                    personId = personId,
                    accountId = accountId
                ),
                attemptState = OrchestratorResponse.AttemptState(
                    attemptId = attemptId,
                    attemptType = "authentication",
                    status = "VERIFIED",
                    missingFields = null,
                    result = mapOf("verified" to true)
                ),
                next = OrchestratorResponse.NextRouting(
                    context = "authentication",
                    step = "authenticated",
                    accountId = accountId,
                    personId = personId
                )
            )
        }
    }

    private fun nextUseStep(p: SmsUsePending): UseStep = when {
        p.tan.isNullOrBlank() -> UseStep.NeedInput(p.missingUserInputs())
        else -> UseStep.VerifyChallenge(p.enrollmentRef, p.tan)
    }

    private fun advanceUse(
        attempt: OrchestratorAttempt,
        pending: SmsUsePending,
        channel: ChannelSession
    ): OrchestratorResponse = when (val step = nextUseStep(pending)) {
        is UseStep.NeedInput -> {
            val missing = step.missingFields
            val attemptId = attempt.attemptId ?: throw IllegalStateException("Attempt has no id")
            val channelId = channel.channelSessionId ?: throw IllegalStateException("Channel has no id")
            pendingStore.save(attempt, pending)
            saveMissingFields(attempt, missing)
            attempt.status = AttemptStatus.INPUT_REQUIRED
            sessionManagementService.updateAttempt(attempt)
            OrchestratorResponse(
                channelSessionId = channelId,
                attemptState = OrchestratorResponse.AttemptState(
                    attemptId = attemptId,
                    attemptType = "authentication",
                    status = "INPUT_REQUIRED",
                    missingFields = missing,
                    result = mapOf("enrollmentId" to pending.enrollmentRef.id)
                ),
                next = OrchestratorResponse.NextRouting(
                    context = "sms",
                    step = "use"
                )
            )
        }
        is UseStep.VerifyChallenge -> {
            val attemptId = attempt.attemptId ?: throw IllegalStateException("Attempt has no id")
            val channelId = channel.channelSessionId ?: throw IllegalStateException("Channel has no id")
            val accountId = channel.accountId ?: throw IllegalStateException("Channel has no account id")
            val processSessionId = attempt.processSessionId
                ?: throw IllegalStateException("Attempt has no process session id")
            try {
                authSmsService.verifyChallenge(step.ref, step.tan)
            } catch (e: Exception) {
                throw OrchestratorException.verificationFailed("TAN validation failed")
            }
            attempt.status = AttemptStatus.VERIFIED
            attempt.result = "{ \"verified\": true }"
            sessionManagementService.updateAttempt(attempt)
            sessionManagementService.updateChannelState(channelId, ChannelState.AUTHENTICATED)
            val personId = sessionManagementService.findProcessSessionById(processSessionId)?.personId
            OrchestratorResponse(
                channelSessionId = channelId,
                processState = OrchestratorResponse.ProcessState(
                    purpose = "LOGIN",
                    status = "COMPLETED",
                    personId = personId,
                    accountId = accountId
                ),
                attemptState = OrchestratorResponse.AttemptState(
                    attemptId = attemptId,
                    attemptType = "authentication",
                    status = "VERIFIED",
                    missingFields = null,
                    result = mapOf("verified" to true)
                ),
                next = OrchestratorResponse.NextRouting(
                    context = "authentication",
                    step = "authenticated",
                    accountId = accountId,
                    personId = personId
                )
            )
        }
    }

    private fun saveMissingFields(attempt: OrchestratorAttempt, missing: List<String>) {
        attempt.missingFields = missing
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", ", "[", "]") { "\"$it\"" }
    }

    private fun getChannelSession(bindingKeyRef: String, channelSessionId: UUID?): ChannelSession {
        val cs = sessionManagementService.findChannelSessionByBindingKeyRef(bindingKeyRef)
            ?: throw OrchestratorException.forbidden("Channel session not found")
        if (channelSessionId != null && cs.channelSessionId != channelSessionId) {
            throw OrchestratorException.forbidden("Channel session mismatch")
        }
        return cs
    }

    private fun getOrCreateLoginSession(channelSession: ChannelSession): LoginProcessSession {
        val channelId = channelSession.channelSessionId
            ?: throw IllegalStateException("Channel session has no id")
        return sessionManagementService
            .findLatestProcessSessionByChannel(channelId, ProcessPurpose.LOGIN) as? LoginProcessSession
            ?: sessionManagementService.createLoginProcessSession(
                channelId, Duration.ofMinutes(15)
            )
    }
}
