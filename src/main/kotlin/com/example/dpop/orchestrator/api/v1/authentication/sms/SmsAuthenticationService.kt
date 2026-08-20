package com.example.dpop.orchestrator.api.v1.authentication.sms

import com.example.dpop.account.AccountService
import com.example.dpop.auth_sms.AuthSmsChallengeResult
import com.example.dpop.auth_sms.AuthSmsEnrollResult
import com.example.dpop.auth_sms.AuthSmsService
import com.example.dpop.auth_sms.EnrollmentRef
import com.example.dpop.orchestrator.api.v1.AttemptPendingStore
import com.example.dpop.orchestrator.api.v1.OrchestratorException
import com.example.dpop.orchestrator.api.v1.OrchestratorResponse
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
        val attempt = sessionManagementService.getAttemptById(attemptId)
            .orElseThrow { OrchestratorException.notFound("Attempt not found") }
        if (attempt.status == AttemptStatus.VERIFIED) {
            throw OrchestratorException.conflict("Attempt is already verified")
        }
        val channelSession = getChannelSession(bindingKeyRef, null)
        var pending = pendingStore.load(attempt, SmsEnrollPending::class.java) ?: SmsEnrollPending.empty()
        pending = pending.merge(patchData)
        return advanceEnroll(attempt, pending, channelSession)
    }

    fun startUse(channelSessionId: UUID, bindingKeyRef: String): OrchestratorResponse {
        val channelSession = getChannelSession(bindingKeyRef, channelSessionId)
        val accountId = channelSession.accountId
            ?: throw OrchestratorException.forbidden("No account bound to channel")

        val enrollmentId = accountService.findActiveSmsEnrollmentId(accountId)
            .orElseThrow { IllegalStateException("No active SMS enrollment found") }
        val ref = EnrollmentRef(enrollmentId)

        val challenge: AuthSmsChallengeResult = authSmsService.startChallenge(ref)

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
            channelId, null,
            OrchestratorResponse.AttemptState(attemptId, "authentication",
                "INPUT_REQUIRED", listOf("tan"), java.util.Map.of("enrollmentId", ref.id)),
            OrchestratorResponse.NextRouting("sms", "use"),
            OrchestratorResponse.DemoHints(challenge.tan,
                "DEMO ONLY – in production the TAN is sent via SMS and never included in the API response")
        )
    }

    fun submitUse(attemptId: UUID, bindingKeyRef: String, patchData: Map<String, Any>?): OrchestratorResponse {
        val attempt = sessionManagementService.getAttemptById(attemptId)
            .orElseThrow { OrchestratorException.notFound("Attempt not found") }
        if (attempt.status == AttemptStatus.VERIFIED) {
            throw OrchestratorException.conflict("Attempt is already verified")
        }
        val channelSession = getChannelSession(bindingKeyRef, null)
        val pending = pendingStore.load(attempt, SmsUsePending::class.java)
            ?: throw IllegalStateException("No pending use data found")
        val merged = pending.merge(patchData)
        return advanceUse(attempt, merged, channelSession)
    }

    fun getStatus(attemptId: UUID, bindingKeyRef: String): OrchestratorResponse {
        val attempt = sessionManagementService.getAttemptById(attemptId)
            .orElseThrow { OrchestratorException.notFound("Attempt not found") }
        var result: Any? = null
        if (attempt.status == AttemptStatus.VERIFIED && attempt.result != null) {
            result = attempt.result
        }
        val attemptIdVal = attempt.attemptId
        return OrchestratorResponse(null, null,
            OrchestratorResponse.AttemptState(
                attemptIdVal, "authentication", attempt.status.toString(), null, result),
            OrchestratorResponse.NextRouting(attempt.nextContext, attempt.nextStep)
        )
    }

    private fun nextEnrollStep(p: SmsEnrollPending): EnrollStep {
        if (p.phoneNumber.isNullOrBlank()) return EnrollStep.NeedInput(p.missingUserInputs())
        if (p.enrollmentRef == null) return EnrollStep.StartEnrollment(p.phoneNumber)
        if (p.tan.isNullOrBlank()) return EnrollStep.NeedInput(listOf("tan"))
        if (!p.tanVerified) return EnrollStep.ConfirmEnrollment(p.enrollmentRef, p.tan)
        if (!p.enrollmentConfirmed) return EnrollStep.ActivateMethod(p.enrollmentRef)
        throw IllegalStateException("Enroll bereits abgeschlossen")
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
            var hints: OrchestratorResponse.DemoHints? = null
            if (missing.contains("tan") && pending.enrollmentRef != null) {
                hints = OrchestratorResponse.DemoHints(pending.tan,
                    "DEMO ONLY – in production the TAN is sent via SMS and never included in the API response")
            }
            OrchestratorResponse(
                channelId, null,
                OrchestratorResponse.AttemptState(attemptId, "authentication",
                    "INPUT_REQUIRED", missing, null),
                OrchestratorResponse.NextRouting("sms", "enroll"),
                hints
            )
        }
        is EnrollStep.StartEnrollment -> {
            val phone = step.phoneNumber
            val result: AuthSmsEnrollResult = authSmsService.startEnrollment(phone!!)
            val attemptId = attempt.attemptId ?: throw IllegalStateException("Attempt has no id")
            val channelId = channel.channelSessionId ?: throw IllegalStateException("Channel has no id")
            val withRef = SmsEnrollPending(
                phone, result.enrollmentRef, null, false, false
            )
            pendingStore.save(attempt, withRef)
            saveMissingFields(attempt, listOf("tan"))
            attempt.status = AttemptStatus.INPUT_REQUIRED
            sessionManagementService.updateAttempt(attempt)
            OrchestratorResponse(
                channelId, null,
                OrchestratorResponse.AttemptState(attemptId, "authentication",
                    "INPUT_REQUIRED", listOf("tan"), null),
                OrchestratorResponse.NextRouting("sms", "enroll"),
                OrchestratorResponse.DemoHints(result.tan,
                    "DEMO ONLY – in production the TAN is sent via SMS and never included in the API response")
            )
        }
        is EnrollStep.ConfirmEnrollment -> {
            val ref = step.ref
            val tan = step.tan
            try {
                authSmsService.confirmEnrollment(ref, tan!!)
            } catch (e: Exception) {
                throw OrchestratorException.verificationFailed("TAN validation failed")
            }
            advanceEnroll(attempt, pending.withTanVerified(), channel)
        }
        is EnrollStep.ActivateMethod -> {
            val ref = step.ref
            val accountId = channel.accountId ?: throw IllegalStateException("Channel has no account id")
            val attemptId = attempt.attemptId ?: throw IllegalStateException("Attempt has no id")
            val channelId = channel.channelSessionId ?: throw IllegalStateException("Channel has no id")
            accountService.addAuthenticationMethod(
                accountId, "sms", true, java.util.HashMap<String, Any>().apply { put("enrollmentId", ref.id!!) } as java.util.Map<String, Any>
            )
            attempt.status = AttemptStatus.VERIFIED
            attempt.result = "{ \"verified\": true }"
            sessionManagementService.updateAttempt(attempt)
            sessionManagementService.updateChannelState(channelId, ChannelState.AUTHENTICATED)
            val processSessionId2 = attempt.processSessionId
                ?: throw IllegalStateException("Attempt has no process session id")
            val processSession = sessionManagementService
                .getProcessSessionById(processSessionId2).orElse(null)
            val personId = processSession?.personId
            OrchestratorResponse(
                channelId,
                OrchestratorResponse.ProcessState("REGISTRATION", "COMPLETED", personId, accountId),
                OrchestratorResponse.AttemptState(attemptId, "authentication", "VERIFIED",
                    null, java.util.Map.of("verified", true)),
                OrchestratorResponse.NextRouting("authentication", "authenticated", null,
                    null, accountId, personId)
            )
        }
    }

    private fun nextUseStep(p: SmsUsePending): UseStep {
        if (p.tan.isNullOrBlank()) return UseStep.NeedInput(p.missingUserInputs())
        return UseStep.VerifyChallenge(p.enrollmentRef!!, p.tan)
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
                channelId, null,
                OrchestratorResponse.AttemptState(attemptId, "authentication",
                    "INPUT_REQUIRED", missing,
                    if (pending.enrollmentRef != null) java.util.Map.of("enrollmentId", pending.enrollmentRef.id!!) else null),
                OrchestratorResponse.NextRouting("sms", "use")
            )
        }
        is UseStep.VerifyChallenge -> {
            val ref = step.ref
            val tan = step.tan
            val attemptId = attempt.attemptId ?: throw IllegalStateException("Attempt has no id")
            val channelId = channel.channelSessionId ?: throw IllegalStateException("Channel has no id")
            val accountId = channel.accountId ?: throw IllegalStateException("Channel has no account id")
            val processSessionId = attempt.processSessionId
                ?: throw IllegalStateException("Attempt has no process session id")
            try {
                authSmsService.verifyChallenge(ref, tan!!)
            } catch (e: Exception) {
                throw OrchestratorException.verificationFailed("TAN validation failed")
            }
            attempt.status = AttemptStatus.VERIFIED
            attempt.result = "{ \"verified\": true }"
            sessionManagementService.updateAttempt(attempt)
            sessionManagementService.updateChannelState(channelId, ChannelState.AUTHENTICATED)
            val processSession = sessionManagementService
                .getProcessSessionById(processSessionId).orElse(null)
            val personId = processSession?.personId
            OrchestratorResponse(
                channelId,
                OrchestratorResponse.ProcessState("LOGIN", "COMPLETED", personId, accountId),
                OrchestratorResponse.AttemptState(attemptId, "authentication", "VERIFIED",
                    null, java.util.Map.of("verified", true)),
                OrchestratorResponse.NextRouting("authentication", "authenticated", null,
                    null, accountId, personId)
            )
        }
    }

    private fun saveMissingFields(attempt: OrchestratorAttempt, missing: List<String>) {
        if (missing.isEmpty()) {
            attempt.missingFields = null
        } else {
            attempt.missingFields = missing.joinToString(", ", "[", "]") { "\"$it\"" }
        }
    }

    private fun getChannelSession(bindingKeyRef: String, channelSessionId: UUID?): ChannelSession {
        val cs = sessionManagementService.getChannelSessionByBindingKeyRef(bindingKeyRef)
            .orElseThrow { OrchestratorException.forbidden("Channel session not found") }
        if (channelSessionId != null && cs.channelSessionId != channelSessionId) {
            throw OrchestratorException.forbidden("Channel session mismatch")
        }
        return cs
    }

    private fun getOrCreateLoginSession(channelSession: ChannelSession): LoginProcessSession {
        val channelId = channelSession.channelSessionId
            ?: throw IllegalStateException("Channel session has no id")
        val existing = sessionManagementService
            .getLatestProcessSessionByChannel(channelId, ProcessPurpose.LOGIN)
        @Suppress("UNCHECKED_CAST")
        return (existing.orElseGet {
            sessionManagementService.createLoginProcessSession(
                channelId, Duration.ofMinutes(15)
            )
        }) as LoginProcessSession
    }
}
