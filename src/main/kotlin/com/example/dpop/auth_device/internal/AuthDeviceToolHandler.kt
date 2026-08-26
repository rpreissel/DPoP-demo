package com.example.dpop.auth_device.internal

import com.example.dpop.auth_device.AuthDeviceDescriptor
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
 */
@Component
class AuthDeviceToolHandler(
    private val descriptor: AuthDeviceDescriptor,
    private val toolDataRepository: AuthDeviceToolDataRepository,
    private val enrollmentRepository: DeviceEnrollmentRepository
) {

    @Transactional
    fun start(toolSessionId: UUID, enrollmentRef: EnrollmentRef): ToolOutcome {
        if (enrollmentRef.type != "device_enrollment") {
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
        return ToolOutcome.InProgress(nextStep = "auth")
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

        if (devicePublicKey.thumbprint != enrollment.thumbprint) {
            return ToolOutcome.Failed("Geraet nicht erkannt")
        }

        return ToolOutcome.Completed.Authenticated(
            amr = listOf(descriptor.method, userVerification.wireValue),
            achievedAcr = descriptor.maxAcr,
            factorTypes = factorTypesFor(userVerification)
        )
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-device tool session: $toolSessionId" }
        return ToolOutcome.InProgress(nextStep = "auth")
    }

    private fun factorTypesFor(userVerification: UserVerification): Set<FactorType> = when (userVerification) {
        UserVerification.PIN -> setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)
        UserVerification.BIOMETRIC -> setOf(FactorType.POSSESSION, FactorType.INHERENCE)
    }
}
