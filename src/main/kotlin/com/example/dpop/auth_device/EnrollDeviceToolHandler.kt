package com.example.dpop.auth_device

import com.example.dpop.auth_device.internal.DeviceEnrollment
import com.example.dpop.auth_device.internal.DeviceEnrollmentRepository
import com.example.dpop.auth_device.internal.EnrollDeviceToolData
import com.example.dpop.auth_device.internal.EnrollDeviceToolDataRepository
import com.example.dpop.tool_spi.DevicePublicKey
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.ToolCategory
import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * toolId=enroll-device (docs/03-tool-architektur.md): registers a device-bound key pair as a new
 * credential. maxAcr=loa2 and factorTypes cover both possible access-means outcomes (pin=KNOWLEDGE,
 * biometric=INHERENCE, plus POSSESSION of the key itself) because a single successful run already
 * combines two factor types on its own - the same "hypothetical passkey with user verification"
 * case docs/03-tool-architektur.md already names.
 *
 * Implements ToolDescriptor directly rather than through a wrapper interface
 * (docs/03-tool-architektur.md #2) - ToolHandlerRegistry collects `List<ToolDescriptor>` straight
 * from Spring.
 */
@Component
class EnrollDeviceToolHandler(
    private val toolDataRepository: EnrollDeviceToolDataRepository,
    private val enrollmentRepository: DeviceEnrollmentRepository
) : ToolDescriptor {

    override val toolId = "enroll-device"
    override val category = ToolCategory.ENROLL
    override val method = "device"
    override val factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE, FactorType.INHERENCE)
    override val maxAcr = "loa2"

    /** Called directly by EnrollDeviceToolController; nothing needs resolving before this can start. */
    @Transactional
    fun start(toolSessionId: UUID): ToolOutcome {
        toolDataRepository.save(EnrollDeviceToolData(toolSessionId = toolSessionId))
        return ToolOutcome.InProgress(nextStep = "enroll")
    }

    /**
     * Called directly by EnrollDeviceToolController, not generically dispatched
     * (docs/08-projektrahmen.md A11). [devicePublicKey]/[accessMeans] arrive already verified by
     * DeviceProofValidator at the call site - this module never parses a raw proof or JWK itself.
     */
    @Transactional
    fun patch(toolSessionId: UUID, devicePublicKey: DevicePublicKey, accessMeans: String): ToolOutcome {
        checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown enroll-device tool session: $toolSessionId" }

        val enrollment = enrollmentRepository.save(
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
            amr = listOf("device", accessMeans),
            achievedAcr = maxAcr,
            factorTypes = factorTypesFor(accessMeans),
            auditDetails = mapOf("thumbprint" to devicePublicKey.thumbprint)
        )
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown enroll-device tool session: $toolSessionId" }
        return ToolOutcome.InProgress(nextStep = "enroll")
    }

    private fun factorTypesFor(accessMeans: String): Set<FactorType> = when (accessMeans) {
        "pin" -> setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)
        "biometric" -> setOf(FactorType.POSSESSION, FactorType.INHERENCE)
        else -> error("Unsupported accessMeans: $accessMeans")
    }
}
