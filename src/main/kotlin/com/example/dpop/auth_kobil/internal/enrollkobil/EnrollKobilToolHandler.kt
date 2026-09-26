package com.example.dpop.auth_kobil.internal.enrollkobil

import com.example.dpop.auth_kobil.EnrollKobilDescriptor
import com.example.dpop.auth_kobil.KOBIL_BINDING_KEY_REF
import com.example.dpop.auth_kobil.KOBIL_DEVICE_ID
import com.example.dpop.auth_kobil.KOBIL_ENROLLMENT_TYPE
import com.example.dpop.auth_kobil.internal.KobilEnrollment
import com.example.dpop.auth_kobil.internal.KobilEnrollmentRepository
import com.example.dpop.auth_kobil.internal.KobilSecrets
import com.example.dpop.auth_kobil.internal.kobilFactorTypes
import com.example.dpop.kobil_mock.KobilSsms
import com.example.dpop.kobil_mock.KobilUserRef
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import com.example.dpop.auth_kobil.api.v1.KobilActivationStep

/**
 * toolId=enroll-kobil: provisions a user at KOBIL, mints the PIN this backend will keep, and
 * turns the device KOBIL binds into an account credential.
 *
 * The PIN leaves this backend exactly twice in a credential's life, and both times the design
 * says so out loud: here, because the SDK's activation call takes it, and once per later
 * authentication run after the client has unlocked. Everywhere else it stays put.
 */
@Component
class EnrollKobilToolHandler(
    private val descriptor: EnrollKobilDescriptor,
    private val toolDataRepository: EnrollKobilToolSessionRepository,
    private val enrollmentRepository: KobilEnrollmentRepository,
    private val secrets: KobilSecrets,
    private val ssms: KobilSsms,
    @Value("\${dpop.kobil.tenant-id:dpop-demo}") private val tenantId: String,
) {

    /**
     * Called directly by EnrollKobilToolController. Everything the app needs for the SDK's
     * `ActivateEvent` is minted here in one go - there is no sensible half-provisioned state to
     * pause in, and a second round trip would only add one.
     */
    @Transactional
    fun start(toolSessionId: UUID): ToolOutcome {
        val user = ssms.provisionUser(tenantId, subjectRef = toolSessionId.toString())
        val activationCode = ssms.issueActivationCode(user)
        val pin = secrets.newPin()
        ssms.setPin(user, pin)
        val unlockSecret = secrets.newUnlockSecret()

        val session = toolDataRepository.save(
            EnrollKobilToolSession(
                toolSessionId = toolSessionId,
                kobilTenantId = user.tenantId,
                kobilUserId = user.userId,
                activationCode = activationCode,
                pin = pin,
                unlockSecret = unlockSecret,
            )
        )

        return outcomeFor(session)
    }

    /**
     * Called directly by EnrollKobilToolController (docs/08-projektrahmen.md A11).
     * [bindingKeyRef] is the enrolling channel's DPoP fingerprint, threaded through so later AUTH
     * candidate resolution offers this credential only on the installation that holds it.
     */
    @Transactional
    fun patch(
        toolSessionId: UUID,
        activated: Boolean?,
        biometricConsent: Boolean?,
        bindingKeyRef: String,
        label: String?,
    ): ToolOutcome {
        val session = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) {
            "Unknown enroll-kobil tool session: $toolSessionId"
        }
        val user = KobilUserRef(session.kobilTenantId, session.kobilUserId)

        // The identifier is asked of KOBIL, never accepted from the client: it is the anchor every
        // later authentication is compared against, so it must not be something the caller chose.
        val deviceId = if (activated == true) ssms.deviceOf(user) else null

        return when (val decision = EnrollKobilFlow.decide(activated, biometricConsent, deviceId)) {
            is EnrollKobilDecision.Unchanged -> outcomeFor(session)

            is EnrollKobilDecision.Enroll -> {
                // Idempotent: a repeated confirmation of the same activation must reuse the row
                // rather than violate the UNIQUE constraint on kobil_user_id.
                val enrollment = enrollmentRepository.findByKobilUserId(session.kobilUserId)
                    ?: enrollmentRepository.save(
                        KobilEnrollment(
                            kobilTenantId = session.kobilTenantId,
                            kobilUserId = session.kobilUserId,
                            kobilDeviceId = decision.deviceId,
                            pin = session.pin,
                            // Only with consent - see EnrollKobilDecision.Enroll.
                            unlockSecretHash = if (decision.biometricConsent) secrets.hash(session.unlockSecret) else null,
                            bindingKeyRef = bindingKeyRef,
                            label = label,
                        )
                    )
                // The activation secrets have done their job: the PIN now lives in the credential,
                // the unlock secret only as its hash. Nothing keeps them in the tool session until
                // the retention sweep (review 2026-09, Phase F - they used to sit there for 24 h).
                session.activationCode = ""
                session.pin = ""
                session.unlockSecret = ""

                ToolOutcome.Completed.Enrolled(
                    enrollmentRef = EnrollmentRef(type = KOBIL_ENROLLMENT_TYPE, id = enrollment.id.toString()),
                    amr = listOf(descriptor.method, decision.userVerification.wireValue),
                    achievedAcr = descriptor.maxAcr,
                    factorTypes = decision.userVerification.kobilFactorTypes(),
                    // The KOBIL user id is not repeated here: it lives in this module's own
                    // enrollment row, where the auth and cleanup paths read it.
                    instanceDetails = mapOf(
                        KOBIL_BINDING_KEY_REF to bindingKeyRef,
                        KOBIL_DEVICE_ID to decision.deviceId,
                    ),
                    label = label,
                )
            }
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        val session = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) {
            "Unknown enroll-kobil tool session: $toolSessionId"
        }
        return outcomeFor(session)
    }

    /**
     * Rebuilt on every read (`ToolControllerSupport.buildReadResponse`), and that is deliberate
     * here: during setup the app still has to *receive* these values, so a reload must be able to
     * pick the flow up again. They stop being handed out the moment the credential exists - from
     * then on the PIN only ever comes back through an authenticated release (`auth-kobil`).
     */
    private fun outcomeFor(session: EnrollKobilToolSession): ToolOutcome.InProgress {
        val (step, fields) = EnrollKobilState.describe()
        return ToolOutcome.InProgress(
            nextStep = step,
            stepData = KobilActivationStep(
                missingFields = fields,
                tenantId = session.kobilTenantId,
                kobilUserId = session.kobilUserId,
                activationCode = session.activationCode,
                // Handed over because the SDK's activation call takes it - the user never sees it.
                pin = session.pin,
                // The app stores this behind its biometric prompt; only its hash outlives setup.
                unlockSecret = session.unlockSecret,
            ),
        )
    }
}
