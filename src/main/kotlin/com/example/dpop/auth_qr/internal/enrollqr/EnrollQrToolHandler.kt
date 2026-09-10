package com.example.dpop.auth_qr.internal.enrollqr

import com.example.dpop.auth_qr.EnrollQrDescriptor
import com.example.dpop.auth_qr.QR_OPTIN_ENROLLMENT_TYPE
import com.example.dpop.auth_qr.internal.QrOptIn
import com.example.dpop.auth_qr.internal.QrOptInRepository
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * toolId=enroll-qr: a pure opt-in, no credential handshake (docs/ideen/qr-login-ueber-app.md #5) -
 * the PATCH call itself IS the confirmation, no field to fill in first.
 *
 * Pure business logic; self-description lives in [EnrollQrDescriptor].
 */
@Component
class EnrollQrToolHandler(
    private val descriptor: EnrollQrDescriptor,
    private val toolDataRepository: EnrollQrToolDataRepository,
    private val qrOptInRepository: QrOptInRepository
) {

    @Transactional
    fun start(toolSessionId: UUID): ToolOutcome {
        toolDataRepository.save(EnrollQrToolData(toolSessionId = toolSessionId))
        return outcomeFor()
    }

    @Transactional
    fun patch(toolSessionId: UUID): ToolOutcome {
        checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown enroll-qr tool session: $toolSessionId" }

        val optIn = qrOptInRepository.save(QrOptIn())
        return ToolOutcome.Completed.Enrolled(
            enrollmentRef = EnrollmentRef(type = QR_OPTIN_ENROLLMENT_TYPE, id = optIn.id.toString()),
            amr = emptyList(),
            achievedAcr = descriptor.maxAcr,
            factorTypes = descriptor.factorTypes
        )
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown enroll-qr tool session: $toolSessionId" }
        return outcomeFor()
    }

    private fun outcomeFor(): ToolOutcome.InProgress = ToolOutcome.InProgress(nextStep = descriptor.startStep)
}
