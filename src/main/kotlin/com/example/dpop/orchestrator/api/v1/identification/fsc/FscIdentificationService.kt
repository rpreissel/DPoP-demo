package com.example.dpop.orchestrator.api.v1.identification.fsc

import com.example.dpop.account.AccountProfile
import com.example.dpop.account.AccountService
import com.example.dpop.ext_stammdaten.ExtStammdatenService
import com.example.dpop.id_fsc.IdFscService
import com.example.dpop.orchestrator.account.AccountBindingKeyMappingService
import com.example.dpop.orchestrator.api.v1.AttemptPendingStore
import com.example.dpop.orchestrator.api.v1.OrchestratorException
import com.example.dpop.orchestrator.api.v1.OrchestratorResponse
import com.example.dpop.orchestrator.session.AttemptStatus
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.session.OrchestratorAttempt
import com.example.dpop.orchestrator.session.ProcessPurpose
import com.example.dpop.orchestrator.session.ProcessSession
import com.example.dpop.orchestrator.session.RegistrationProcessSession
import com.example.dpop.orchestrator.session.SessionManagementService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.util.UUID

@Service
@Transactional
class FscIdentificationService(
    private val sessionManagementService: SessionManagementService,
    private val extStammdatenService: ExtStammdatenService,
    private val idFscService: IdFscService,
    private val accountService: AccountService,
    private val accountBindingKeyMappingService: AccountBindingKeyMappingService,
    private val pendingStore: AttemptPendingStore
) {

    fun startIdentification(
        channelSessionId: UUID,
        bindingKeyRef: String,
        data: Map<String, Any>?
    ): OrchestratorResponse {
        val channelSession = getChannelSession(bindingKeyRef, channelSessionId)
        val channelId = channelSession.channelSessionId
            ?: throw IllegalStateException("Channel session has no id")

        val processSession = sessionManagementService
            .getLatestProcessSessionByChannel(channelId, ProcessPurpose.REGISTRATION)
            .orElseThrow { IllegalArgumentException("Process session not found") }
        @Suppress("UNCHECKED_CAST")
        val registrationSession = processSession as RegistrationProcessSession
        registrationSession.selectedIdentificationMethod = "fsc"
        sessionManagementService.updateProcessSession(registrationSession)

        val processSessionId = registrationSession.processSessionId
            ?: throw IllegalStateException("Process session has no id")

        val attempt = sessionManagementService.createIdentificationAttempt(
            processSessionId, "fsc", "input", Duration.ofMinutes(10)
        )

        val pending = FscPending.empty().merge(data)
        return advance(attempt, pending, bindingKeyRef, channelSession, registrationSession)
    }

    fun submitIdentificationData(
        attemptId: UUID,
        bindingKeyRef: String,
        patchData: Map<String, Any>?
    ): OrchestratorResponse {
        val attempt = sessionManagementService.getAttemptById(attemptId)
            .orElseThrow { OrchestratorException.notFound("Attempt not found") }

        if (attempt.status == AttemptStatus.VERIFIED) {
            throw OrchestratorException.conflict("Attempt is already verified")
        }

        val stored = pendingStore.load(attempt, FscPending::class.java)
        val pending = (stored ?: FscPending.empty()).merge(patchData)

        val channelSession = sessionManagementService.getChannelSessionByBindingKeyRef(bindingKeyRef)
            .orElseThrow { OrchestratorException.forbidden("Channel session not found") }
        val processSessionId = attempt.processSessionId
            ?: throw IllegalStateException("Attempt has no process session id")
        val processSession = sessionManagementService
            .getProcessSessionById(processSessionId)
            .orElseThrow { IllegalArgumentException("Process session not found") }

        return advance(attempt, pending, bindingKeyRef, channelSession, processSession)
    }

    fun getIdentificationStatus(attemptId: UUID, bindingKeyRef: String): OrchestratorResponse {
        val attempt = sessionManagementService.getAttemptById(attemptId)
            .orElseThrow { OrchestratorException.notFound("Attempt not found") }

        val result: Any? = if (attempt.status == AttemptStatus.VERIFIED) attempt.result else null
        return OrchestratorResponse(null, null,
            OrchestratorResponse.AttemptState(
                attempt.attemptId, "identification",
                attempt.status.toString(), null, result),
            OrchestratorResponse.NextRouting(attempt.nextContext, attempt.nextStep)
        )
    }

    private fun advance(
        attempt: OrchestratorAttempt,
        pending: FscPending,
        bindingKeyRef: String,
        channelSession: ChannelSession,
        processSession: ProcessSession
    ): OrchestratorResponse = when (val step = nextStep(pending)) {
        is FscStep.NeedInput -> {
            val missing = step.missingFields
            val attemptId = attempt.attemptId ?: throw IllegalStateException("Attempt has no id")
            val channelId = channelSession.channelSessionId ?: throw IllegalStateException("Channel has no id")
            pendingStore.save(attempt, pending)
            attempt.status = AttemptStatus.INPUT_REQUIRED
            saveMissingFields(attempt, missing)
            sessionManagementService.updateAttempt(attempt)
            OrchestratorResponse(
                channelId,
                OrchestratorResponse.ProcessState("REGISTRATION", "ACTIVE", null, null),
                OrchestratorResponse.AttemptState(attemptId, "identification",
                    "INPUT_REQUIRED", missing, null),
                OrchestratorResponse.NextRouting("fsc", "input")
            )
        }
        is FscStep.Verify -> {
            verifyFsc(attempt, step.kvnr, step.fsc, bindingKeyRef, channelSession, processSession)
        }
    }

    private fun nextStep(p: FscPending): FscStep {
        val missing = p.missingFields()
        return if (missing.isEmpty()) FscStep.Verify(p.kvnr, p.fsc) else FscStep.NeedInput(missing)
    }

    private fun verifyFsc(
        attempt: OrchestratorAttempt,
        kvnr: String?,
        fsc: String?,
        bindingKeyRef: String,
        channelSession: ChannelSession,
        processSession: ProcessSession
    ): OrchestratorResponse {
        val personId = extStammdatenService.findPersonIdByKvnr(kvnr!!).orElse(null)
        if (personId == null || !idFscService.validateFsc(personId, fsc!!)) {
            val attemptId = attempt.attemptId ?: throw IllegalStateException("Attempt has no id")
            attempt.status = AttemptStatus.FAILED
            attempt.result = "{}"
            sessionManagementService.updateAttempt(attempt)
            throw OrchestratorException.verificationFailed("FSC validation failed")
        }

        attempt.status = AttemptStatus.VERIFIED
        attempt.result = "{ \"identified\": true, \"personId\": $personId }"
        sessionManagementService.updateAttempt(attempt)

        processSession.personId = personId
        sessionManagementService.updateProcessSession(processSession)

        val processSessionId = processSession.processSessionId
            ?: throw IllegalStateException("Process session has no id")
        val accountId = channelSession.accountId
            ?: throw IllegalStateException("Channel has no account id")
        val attemptId = attempt.attemptId ?: throw IllegalStateException("Attempt has no id")
        val channelId = channelSession.channelSessionId ?: throw IllegalStateException("Channel has no id")

        val account: AccountProfile = accountService.identifyAccount(
            personId, "fsc", "HIGH", processSessionId,
            java.util.HashMap<String, Any>().apply { put("kvnr", kvnr!!) } as java.util.Map<String, Any>
        )
        sessionManagementService.setAccountId(channelId, account.accountId!!)
        accountBindingKeyMappingService.mapBindingKeyToAccount(bindingKeyRef, account.accountId!!)

        val hasAuth = account.activeAuthenticationMethods.isNotEmpty()
        val next = if (hasAuth)
            OrchestratorResponse.NextRouting("authentication", "selectMethod",
                account.activeAuthenticationMethods, null, account.accountId!!, personId)
        else
            OrchestratorResponse.NextRouting("enrollment", "selectMethod",
                listOf("sms"), null, account.accountId!!, personId)

        return OrchestratorResponse(
            channelId,
            OrchestratorResponse.ProcessState("REGISTRATION", "ACTIVE", personId, account.accountId!!),
            OrchestratorResponse.AttemptState(attemptId, "identification", "VERIFIED",
                null, java.util.Map.of("identified", true, "personId", personId)),
            next
        )
    }

    private fun getChannelSession(bindingKeyRef: String, channelSessionId: UUID?): ChannelSession {
        val cs = sessionManagementService.getChannelSessionByBindingKeyRef(bindingKeyRef)
            .orElseThrow { OrchestratorException.forbidden("Channel session not found") }
        if (channelSessionId != null && cs.channelSessionId != channelSessionId) {
            throw OrchestratorException.forbidden("Channel session mismatch")
        }
        return cs
    }

    private fun saveMissingFields(attempt: OrchestratorAttempt, missing: List<String>) {
        attempt.missingFields = missing.joinToString(", ", "[", "]") { "\"$it\"" }
    }
}
