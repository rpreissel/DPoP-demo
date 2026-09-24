package com.example.dpop.auth_kobil.internal.authkobil

import com.example.dpop.texts.Text
import com.example.dpop.auth_kobil.AuthKobilDescriptor
import com.example.dpop.auth_kobil.api.v1.KobilUnlockCredential
import com.example.dpop.auth_kobil.internal.KobilEnrollment
import com.example.dpop.auth_kobil.internal.KobilEnrollmentRepository
import com.example.dpop.auth_kobil.internal.KobilSecrets
import com.example.dpop.auth_kobil.internal.kobilFactorTypes
import com.example.dpop.kobil_mock.KobilRisk
import com.example.dpop.kobil_mock.KobilSsms
import com.example.dpop.kobil_mock.KobilUserRef
import com.example.dpop.tool_api.PasswordCredentialPort
import com.example.dpop.tool_api.UserVerification
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import com.example.dpop.auth_kobil.api.v1.KobilOtpStep

/**
 * toolId=auth-kobil: releases the backend-held PIN to an app that has unlocked locally, then
 * proves the device by redeeming the one-time password that app got back from KOBIL.
 *
 * The property that makes this tool different from every other one here: the client never carries
 * the proof. It carries a reference, and this backend fetches the assertion from KOBIL itself. A
 * tampered client can withhold or replay an OTP; it cannot assert an outcome.
 */
@Component
class AuthKobilToolHandler(
    private val descriptor: AuthKobilDescriptor,
    private val toolDataRepository: AuthKobilToolSessionRepository,
    private val enrollmentRepository: KobilEnrollmentRepository,
    private val secrets: KobilSecrets,
    private val ssms: KobilSsms,
    private val passwordCredentials: PasswordCredentialPort,
    /**
     * Which reported signals this deployment refuses to authenticate through. A named set, not a
     * score: a score would have to be invented, and an invented number reads as a measurement.
     */
    @Value("\${dpop.kobil.blocking-risks:ROOTED,EMULATOR,DEBUGGER_ATTACHED,APP_TAMPERED}")
    private val blockingRisks: Set<KobilRisk>,
    @Value("\${dpop.kobil.pin-release-ttl-seconds:120}") private val pinReleaseTtlSeconds: Long,
) {

    /**
     * @param passwordAvailable whether the account still holds an active password credential -
     * resolved by the controller, because only the orchestrator side can see the account's other
     * methods. It decides whether the password unlock is offered at all.
     */
    @Transactional
    fun start(toolSessionId: UUID, enrollmentRef: EnrollmentRef, passwordAvailable: Boolean): ToolOutcome {
        val session = toolDataRepository.save(
            AuthKobilToolSession(
                toolSessionId = toolSessionId,
                enrollmentRefType = enrollmentRef.type,
                enrollmentRefId = enrollmentRef.id,
            )
        )
        return inProgress(stateOf(session, passwordAvailable))
    }

    /**
     * Hands the PIN over - once, in this response only. It is never written into the step state,
     * because that state is rebuilt on every read and would hand the PIN out again to anyone
     * holding the tool session URL.
     *
     * @param passwordEnrollment the account's active password credential, or null if it has none.
     * Nullable on purpose: [PasswordCredentialPort.verify] must be called either way so that an
     * account without a password costs exactly as much as a wrong one.
     */
    @Transactional
    fun releasePin(
        toolSessionId: UUID,
        unlock: KobilUnlockCredential,
        passwordEnrollment: EnrollmentRef?,
        accountId: Long?,
    ): ToolOutcome {
        val session = loadSession(toolSessionId)
        val enrollment = loadEnrollment(session)

        val unlocked = when (unlock) {
            is KobilUnlockCredential.BiometricUnlock ->
                secrets.matches(unlock.unlockSecret, enrollment.unlockSecretHash)
            is KobilUnlockCredential.PasswordUnlock ->
                passwordCredentials.verify(passwordEnrollment, unlock.password)
        }

        // One wording for every way this can fail - wrong secret, wrong password, or no password
        // credential at all. Telling them apart would answer questions about the account that the
        // caller has not proven a right to ask. A repeated release is NOT a failure: an app whose
        // window closed simply unlocks again, and the new release replaces the old one.
        if (!unlocked) {
            return ToolOutcome.Failed(Text("Entsperren fehlgeschlagen"), attemptedAccountId = accountId)
        }

        session.release(unlock.userVerification, Instant.now().plusSeconds(pinReleaseTtlSeconds))

        val (step, fields) = AuthKobilState.AwaitingOtp(enrollment.kobilTenantId, enrollment.kobilUserId).describe()
        return ToolOutcome.InProgress(
            nextStep = step,
            // The released PIN belongs to this one response - see KobilOtpStep.kobilPin.
            stepData = (fields as KobilOtpStep).copy(kobilPin = enrollment.pin),
        )
    }

    /**
     * The step this session is in, derived from whether a release still counts - never stored
     * twice. A released PIN is not part of it: that value belongs to one response only.
     */
    private fun stateOf(session: AuthKobilToolSession, passwordAvailable: Boolean): AuthKobilState {
        val enrollment = loadEnrollment(session)
        if (session.liveRelease(Instant.now()) != null) {
            return AuthKobilState.AwaitingOtp(enrollment.kobilTenantId, enrollment.kobilUserId)
        }
        // Derived, never assumed: a stored hash exists exactly when biometrics was consented to
        // (KobilEnrollment.unlockSecretHash), and the password is only a way in while the account
        // still has one. An empty list is possible and honest - the credential then has no way in
        // left, and the client can say so instead of offering something that cannot work.
        val options = buildList {
            if (enrollment.unlockSecretHash != null) add(UserVerification.BIOMETRIC.wireValue)
            if (passwordAvailable) add("password")
        }
        return AuthKobilState.Unlock(enrollment.kobilTenantId, enrollment.kobilUserId, options)
    }

    private fun inProgress(state: AuthKobilState): ToolOutcome.InProgress {
        val (step, fields) = state.describe()
        return ToolOutcome.InProgress(nextStep = step, stepData = fields)
    }

    /** Redeems the one-time password at KOBIL and decides on the assertion behind it. */
    @Transactional
    fun patch(toolSessionId: UUID, otp: String?, accountId: Long?): ToolOutcome {
        val session = loadSession(toolSessionId)
        val enrollment = loadEnrollment(session)
        val release = session.liveRelease(Instant.now())

        val verification = if (release != null && otp != null) {
            ssms.verifyOtp(KobilUserRef(enrollment.kobilTenantId, enrollment.kobilUserId), otp)
        } else {
            null
        }

        return when (val decision = AuthKobilFlow.decide(verification, enrollment.kobilDeviceId, release, blockingRisks)) {
            is AuthKobilDecision.NotReleased ->
                ToolOutcome.Failed(Text("Entsperren erforderlich"), attemptedAccountId = accountId)

            is AuthKobilDecision.OtpInvalid ->
                ToolOutcome.Failed(Text("Bestaetigung nicht erkannt"), attemptedAccountId = accountId)

            // Same wording auth-device uses: it must not reveal which device was expected.
            is AuthKobilDecision.WrongDevice ->
                ToolOutcome.Failed(Text("Geraet nicht erkannt"), attemptedAccountId = accountId)

            // Deliberately its own reason. This is not a user's slip but a statement about the
            // device; folding it into "not recognized" would swallow a real finding.
            is AuthKobilDecision.RiskRejected ->
                ToolOutcome.Failed(Text("Geraet als unsicher gemeldet"), attemptedAccountId = accountId)

            // No auditDetails: unlike every Completed variant that creates something,
            // Authenticated carries none (tool_spi.ToolOutcome). Non-blocking signals a run saw
            // (e.g. OS_OUTDATED) therefore go unrecorded - a gap worth widening the SPI for one
            // day, not worth a module-local log that nothing else can read.
            is AuthKobilDecision.Complete -> ToolOutcome.Completed.Authenticated(
                amr = listOf(descriptor.method, decision.userVerification.wireValue),
                achievedAcr = descriptor.maxAcr,
                factorTypes = decision.userVerification.kobilFactorTypes(),
            )
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID, passwordAvailable: Boolean): ToolOutcome =
        inProgress(stateOf(loadSession(toolSessionId), passwordAvailable))

    private fun loadSession(toolSessionId: UUID): AuthKobilToolSession =
        checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) {
            "Unknown auth-kobil tool session: $toolSessionId"
        }

    private fun loadEnrollment(session: AuthKobilToolSession): KobilEnrollment {
        val id = checkNotNull(session.enrollmentRefId) { "auth-kobil session without enrollment reference" }
        return checkNotNull(enrollmentRepository.findByIdOrNull(id.toLong())) {
            "auth-kobil enrollment $id no longer exists"
        }
    }
}
