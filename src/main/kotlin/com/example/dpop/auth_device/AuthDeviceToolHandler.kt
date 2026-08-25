package com.example.dpop.auth_device

import com.example.dpop.auth_device.internal.AuthDeviceToolData
import com.example.dpop.auth_device.internal.AuthDeviceToolDataRepository
import com.example.dpop.auth_device.internal.DeviceEnrollmentRepository
import com.example.dpop.tool_spi.DevicePublicKey
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.ToolDescriptor
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
 * Implements ToolDescriptor directly rather than through a wrapper interface
 * (docs/03-tool-architektur.md #2) - ToolHandlerRegistry collects `List<ToolDescriptor>` straight
 * from Spring.
 */
@Component
class AuthDeviceToolHandler(
    private val toolDataRepository: AuthDeviceToolDataRepository,
    private val enrollmentRepository: DeviceEnrollmentRepository
) : ToolDescriptor {

    override val toolId = "auth-device"
    override val role = MethodRole.DEVICE_AUTH
    override val methodFamily = DEVICE_METHOD
    override val factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE, FactorType.INHERENCE)
    override val maxAcr = "loa2"
    // Declared independently per tool variant, same as maxAcr/factorTypes - not a fact that must
    // be identical across every tool sharing this method (a future LOOKUP_AUTH sibling, for
    // instance, could legitimately answer differently). Callers that need this for a SPECIFIC
    // tool resolve its own descriptor unambiguously by (method, role) - MethodRole, unlike
    // category, fully distinguishes DEVICE_AUTH from LOOKUP_AUTH - never an arbitrary descriptor
    // picked by method name alone (DefaultAuthPolicy.candidateTools).
    override val allowsMultipleInstances = true

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
     * (docs/08-projektrahmen.md A11). [devicePublicKey]/[accessMeans] arrive already verified by
     * DeviceProofValidator at the call site.
     */
    @Transactional
    fun patch(toolSessionId: UUID, devicePublicKey: DevicePublicKey, accessMeans: String): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-device tool session: $toolSessionId" }
        val enrollmentId = checkNotNull(data.enrollmentRefId?.toLongOrNull()) { "auth-device tool session without enrollment ref: $toolSessionId" }
        val enrollment = checkNotNull(enrollmentRepository.findByIdOrNull(enrollmentId)) { "Geraete-Enrollment nicht gefunden: $enrollmentId" }

        if (devicePublicKey.thumbprint != enrollment.thumbprint) {
            return ToolOutcome.Failed("Geraet nicht erkannt")
        }

        return ToolOutcome.Completed.Authenticated(
            amr = listOf("device", accessMeans),
            achievedAcr = maxAcr,
            factorTypes = factorTypesFor(accessMeans)
        )
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-device tool session: $toolSessionId" }
        return ToolOutcome.InProgress(nextStep = "auth")
    }

    private fun factorTypesFor(accessMeans: String): Set<FactorType> = when (accessMeans) {
        "pin" -> setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)
        "biometric" -> setOf(FactorType.POSSESSION, FactorType.INHERENCE)
        else -> error("Unsupported accessMeans: $accessMeans")
    }
}
