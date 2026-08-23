package com.example.dpop.orchestrator.orchestration

import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.orchestrator.session.ProcessPurpose
import com.example.dpop.orchestrator.session.ProcessSession
import com.example.dpop.orchestrator.session.SessionManagementService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * User-initiated abandonment of the current process - distinct from Failed (retry-exhausted).
 * The channel-state reversal per purpose is already anticipated by the ChannelState diagram in
 * docs/02-domaenenmodell.md #3: "REGISTERING --> ANONYMOUS: cancel or timeout" and
 * "STEP_UP_IN_PROGRESS --> AUTHENTICATED: step-up not required anymore".
 */
@Service
@Transactional
class ProcessCancellationService(private val sessionManagementService: SessionManagementService) {

    fun cancel(processSession: ProcessSession, channelSession: ChannelSession) {
        processSession.cancel()
        sessionManagementService.updateProcessSession(processSession)
        sessionManagementService.recordEvent(
            channelSession.channelSessionId, processSession.processSessionId, "PROCESS_CANCELLED", "orchestrator"
        )

        when (processSession.purpose) {
            ProcessPurpose.REGISTRATION -> {
                // Full reset: a half-identified channel isn't a returning user - don't leave it
                // bound to an account it has no usable authentication method for yet.
                channelSession.accountId = null
                channelSession.authContextId = null
                channelSession.state = ChannelState.ANONYMOUS
                sessionManagementService.updateChannelSession(channelSession)
            }
            ProcessPurpose.STEP_UP -> sessionManagementService.updateChannelState(
                channelSession.channelSessionId!!, ChannelState.AUTHENTICATED
            )
            ProcessPurpose.LOGIN, null -> Unit // channel state was never advanced past its pre-login value
        }
    }
}
