package com.example.dpop.auth_device.internal

import com.example.dpop.auth_device.EnrollDeviceDescriptor
import com.example.dpop.tool_api.DevicePublicKey
import com.example.dpop.tool_api.UserVerification
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * toolId=enroll-device (docs/03-tool-architektur.md): registers a device-bound key pair as a new
 * credential.
 *
 * Pure business logic; self-description lives in [EnrollDeviceDescriptor] (DPoP-demo-vun).
 */
@Component
class EnrollDeviceToolHandler(
    private val descriptor: EnrollDeviceDescriptor,
    private val toolDataRepository: EnrollDeviceToolDataRepository,
    private val enrollmentRepository: DeviceEnrollmentRepository
) {

    /** Called directly by EnrollDeviceToolController; nothing needs resolving before this can start. */
    @Transactional
    fun start(toolSessionId: UUID): ToolOutcome {
        toolDataRepository.save(EnrollDeviceToolData(toolSessionId = toolSessionId))
        return ToolOutcome.InProgress(nextStep = "enroll")
    }

    /**
     * Called directly by EnrollDeviceToolController, not generically dispatched
     * (docs/08-projektrahmen.md A11). [devicePublicKey]/[userVerification] arrive already verified by
     * DeviceProofValidator at the call site - this module never parses a raw proof or JWK itself.
     * [deviceBindingKeyRef] is the enrolling channel's own DPoP-proven device fingerprint,
     * threaded through so later AUTH candidate resolution can offer/resolve this credential only
     * on the exact physical device that holds it (docs/04-orchestrierung.md).
     */
    @Transactional
    fun patch(toolSessionId: UUID, devicePublicKey: DevicePublicKey, userVerification: UserVerification, deviceBindingKeyRef: String, label: String?): ToolOutcome {
        checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown enroll-device tool session: $toolSessionId" }

        // Idempotent: getOrCreateDeviceKeyPair() on the client always returns the SAME key once
        // generated, so re-enrolling on the same device (e.g. after a rename or a lost link)
        // would resubmit the same thumbprint - reuse that row instead of a second INSERT, which
        // would violate the UNIQUE constraint on device_enrollment.thumbprint.
        val enrollment = enrollmentRepository.findByThumbprint(devicePublicKey.thumbprint)
            ?: enrollmentRepository.save(
                DeviceEnrollment(
                    kty = devicePublicKey.kty,
                    crv = devicePublicKey.crv,
                    x = devicePublicKey.x,
                    y = devicePublicKey.y,
                    thumbprint = devicePublicKey.thumbprint
                )
            )

        return ToolOutcome.Completed.Enrolled(
            enrollmentRef = EnrollmentRef(type = "device_enrollment", id = enrollment.id.toString()),
            amr = listOf(descriptor.method, userVerification.wireValue),
            achievedAcr = descriptor.maxAcr,
            factorTypes = factorTypesFor(userVerification),
            auditDetails = mapOf("thumbprint" to devicePublicKey.thumbprint, "deviceBindingKeyRef" to deviceBindingKeyRef, "label" to label)
        )
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown enroll-device tool session: $toolSessionId" }
        return ToolOutcome.InProgress(nextStep = "enroll")
    }

    private fun factorTypesFor(userVerification: UserVerification): Set<FactorType> = when (userVerification) {
        UserVerification.PIN -> setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)
        UserVerification.BIOMETRIC -> setOf(FactorType.POSSESSION, FactorType.INHERENCE)
    }
}
