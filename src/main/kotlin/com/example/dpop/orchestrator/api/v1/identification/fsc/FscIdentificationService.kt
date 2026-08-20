package com.example.dpop.orchestrator.api.v1.identification.fsc

import com.example.dpop.account.AccountProfile
import com.example.dpop.account.AccountService
import com.example.dpop.ext_stammdaten.ExtStammdatenService
import com.example.dpop.id_fsc.IdFscService
import com.example.dpop.orchestrator.account.AccountBindingKeyMappingService
import com.example.dpop.orchestrator.api.v1.AttemptPendingStore
import com.example.dpop.orchestrator.api.v1.OrchestratorException
import com.example.dpop.orchestrator.api.v1.OrchestratorResponse
import com.example.dpop.orchestrator.api.v1.load
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
            .findLatestProcessSessionByChannel(channelId, ProcessPurpose.REGISTRATION)
            ?: throw IllegalArgumentException("Process session not found")
        val registrationSession = processSession as? RegistrationProcessSession
            ?: throw IllegalStateException("Process session is not a registration session")
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
        val attempt = sessionManagementService.findAttemptById(attemptId)
            ?: throw OrchestratorException.notFound("Attempt not found")

        if (attempt.status == AttemptStatus.VERIFIED) {
            throw OrchestratorException.conflict("Attempt is already verified")
        }

        val pending = pendingStore.load<FscPending>(attempt)
            ?.merge(patchData)
            ?: FscPending.empty().merge(patchData)

        val channelSession = sessionManagementService.findChannelSessionByBindingKeyRef(bindingKeyRef)
            ?: throw OrchestratorException.forbidden("Channel session not found")
        val processSessionId = attempt.processSessionId
            ?: throw IllegalStateException("Attempt has no process session id")
        val processSession = sessionManagementService
            .findProcessSessionById(processSessionId)
            ?: throw IllegalArgumentException("Process session not found")

        return advance(attempt, pending, bindingKeyRef, channelSession, processSession)
    }

    fun getIdentificationStatus(attemptId: UUID, bindingKeyRef: String): OrchestratorResponse {
        val attempt = sessionManagementService.findAttemptById(attemptId)
            ?: throw OrchestratorException.notFound("Attempt not found")

        val result = attempt.result.takeIf { attempt.status == AttemptStatus.VERIFIED }
        return OrchestratorResponse(
            channelSessionId = null,
            attemptState = OrchestratorResponse.AttemptState(
                attemptId = attempt.attemptId,
                attemptType = "identification",
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
                channelSessionId = channelId,
                processState = OrchestratorResponse.ProcessState(
                    purpose = "REGISTRATION",
                    status = "ACTIVE",
                    personId = null,
                    accountId = null
                ),
                attemptState = OrchestratorResponse.AttemptState(
                    attemptId = attemptId,
                    attemptType = "identification",
                    status = "INPUT_REQUIRED",
                    missingFields = missing,
                    result = null
                ),
                next = OrchestratorResponse.NextRouting(
                    context = "fsc",
                    step = "input"
                )
            )
        }
        is FscStep.Verify -> {
            verifyFsc(attempt, step.kvnr, step.fsc, bindingKeyRef, channelSession, processSession)
        }
    }

    private fun nextStep(p: FscPending): FscStep {
        val kvnr = p.kvnr
        val fsc = p.fsc
        return if (kvnr.isNullOrBlank() || fsc.isNullOrBlank()) {
            FscStep.NeedInput(p.missingFields())
        } else {
            FscStep.Verify(kvnr, fsc)
        }
    }

    private fun verifyFsc(
        attempt: OrchestratorAttempt,
        kvnr: String,
        fsc: String,
        bindingKeyRef: String,
        channelSession: ChannelSession,
        processSession: ProcessSession
    ): OrchestratorResponse {
        val personId = extStammdatenService.findPersonIdByKvnr(kvnr)
        if (personId == null || !idFscService.validateFsc(personId, fsc)) {
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
        val attemptId = attempt.attemptId ?: throw IllegalStateException("Attempt has no id")
        val channelId = channelSession.channelSessionId ?: throw IllegalStateException("Channel has no id")

        val account: AccountProfile = accountService.identifyAccount(
            personId, "fsc", "HIGH", processSessionId,
            mapOf("kvnr" to kvnr)
        )
        val accountId = account.accountId
            ?: throw IllegalStateException("Account has no id")
        sessionManagementService.bindAccountId(channelId, accountId)
        accountBindingKeyMappingService.mapBindingKeyToAccount(bindingKeyRef, accountId)

        val hasAuth = account.activeAuthenticationMethods.isNotEmpty()
        val next = if (hasAuth) {
            OrchestratorResponse.NextRouting(
                context = "authentication",
                step = "selectMethod",
                methods = account.activeAuthenticationMethods,
                accountId = accountId,
                personId = personId
            )
        } else {
            OrchestratorResponse.NextRouting(
                context = "enrollment",
                step = "selectMethod",
                methods = listOf("sms"),
                accountId = accountId,
                personId = personId
            )
        }

        return OrchestratorResponse(
            channelSessionId = channelId,
            processState = OrchestratorResponse.ProcessState(
                purpose = "REGISTRATION",
                status = "ACTIVE",
                personId = personId,
                accountId = accountId
            ),
            attemptState = OrchestratorResponse.AttemptState(
                attemptId = attemptId,
                attemptType = "identification",
                status = "VERIFIED",
                missingFields = null,
                result = mapOf("identified" to true, "personId" to personId)
            ),
            next = next
        )
    }

    private fun getChannelSession(bindingKeyRef: String, channelSessionId: UUID?): ChannelSession {
        val cs = sessionManagementService.findChannelSessionByBindingKeyRef(bindingKeyRef)
            ?: throw OrchestratorException.forbidden("Channel session not found")
        if (channelSessionId != null && cs.channelSessionId != channelSessionId) {
            throw OrchestratorException.forbidden("Channel session mismatch")
        }
        return cs
    }

    private fun saveMissingFields(attempt: OrchestratorAttempt, missing: List<String>) {
        attempt.missingFields = missing
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", ", "[", "]") { "\"$it\"" }
    }
}
