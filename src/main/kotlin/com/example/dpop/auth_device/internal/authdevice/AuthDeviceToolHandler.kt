package com.example.dpop.auth_device.internal.authdevice
import com.example.dpop.auth_device.internal.DeviceEnrollmentRepository

import com.example.dpop.auth_device.AuthDeviceDescriptor
import com.example.dpop.auth_device.DEVICE_ENROLLMENT_TYPE
import com.example.dpop.tool_api.DevicePublicKey
import com.example.dpop.tool_api.UserVerification
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.ToolOutcome
import com.example.dpop.tool_spi.UnresolvableReferenceException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * toolId=auth-device (docs/03-tool-architektur.md). [start]'s [enrollmentRef] must be the
 * account's active device enrollment - resolved and null-checked by AuthDeviceToolController
 * before calling this (never null here), since this module never reads `account` itself.
 *
 * No server-issued challenge is needed: the device proof's htu claim already binds it to this
 * exact, single-use toolSessionId URL, and jti+thumbprint+iat-window replay protection
 * (DeviceProofValidator) covers the rest - the same no-nonce model ordinary DPoP proofs already
 * use in this app.
 *
 * Pure business logic; self-description lives in [AuthDeviceDescriptor] (DPoP-demo-vun).
 * Delegates the match decision to [AuthDeviceFlow].
 */
@Component
class AuthDeviceToolHandler(
    private val descriptor: AuthDeviceDescriptor,
    private val toolDataRepository: AuthDeviceToolDataRepository,
    private val enrollmentRepository: DeviceEnrollmentRepository
) {

    @Transactional
    fun start(toolSessionId: UUID, enrollmentRef: EnrollmentRef): ToolOutcome {
        if (enrollmentRef.type != DEVICE_ENROLLMENT_TYPE) {
            throw UnresolvableReferenceException("Unerwarteter Enrollment-Typ: ${enrollmentRef.type}")
        }
        val enrollmentId = enrollmentRef.id.toLongOrNull()
            ?: throw UnresolvableReferenceException("Ungueltige Enrollment-Referenz: ${enrollmentRef.id}")
        enrollmentRepository.findByIdOrNull(enrollmentId)
            ?: throw UnresolvableReferenceException("Geraete-Enrollment nicht gefunden: ${enrollmentRef.id}")

        toolDataRepository.save(
            AuthDeviceToolData(
                toolSessionId = toolSessionId,
                enrollmentRefType = enrollmentRef.type,
                enrollmentRefId = enrollmentRef.id
            )
        )
        return outcomeFor()
    }

    /**
     * Called directly by AuthDeviceToolController, not generically dispatched
     * (docs/08-projektrahmen.md A11). [devicePublicKey]/[userVerification] arrive already verified by
     * DeviceProofValidator at the call site.
     */
    @Transactional
    fun patch(toolSessionId: UUID, devicePublicKey: DevicePublicKey, userVerification: UserVerification): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-device tool session: $toolSessionId" }
        val enrollmentId = checkNotNull(data.enrollmentRefId?.toLongOrNull()) { "auth-device tool session without enrollment ref: $toolSessionId" }
        val enrollment = checkNotNull(enrollmentRepository.findByIdOrNull(enrollmentId)) { "Geraete-Enrollment nicht gefunden: $enrollmentId" }

        return when (val decision = AuthDeviceFlow.decide(devicePublicKey.thumbprint, enrollment.thumbprint, userVerification)) {
            AuthDeviceDecision.WrongDevice -> ToolOutcome.Failed("Geraet nicht erkannt")
            is AuthDeviceDecision.Complete -> ToolOutcome.Completed.Authenticated(
                amr = listOf(descriptor.method, decision.userVerification.wireValue),
                achievedAcr = descriptor.maxAcr,
                factorTypes = factorTypesFor(decision.userVerification)
            )
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-device tool session: $toolSessionId" }
        return outcomeFor()
    }

    private fun outcomeFor(): ToolOutcome.InProgress {
        val (step, fields) = AuthDeviceState.describe()
        return ToolOutcome.InProgress(nextStep = step, data = fields)
    }

    private fun factorTypesFor(userVerification: UserVerification): Set<FactorType> = when (userVerification) {
        UserVerification.PIN -> setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)
        UserVerification.BIOMETRIC -> setOf(FactorType.POSSESSION, FactorType.INHERENCE)
    }
}
