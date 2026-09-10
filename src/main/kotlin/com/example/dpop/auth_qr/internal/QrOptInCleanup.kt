package com.example.dpop.auth_qr.internal

import com.example.dpop.auth_qr.QR_OPTIN_ENROLLMENT_TYPE
import com.example.dpop.tool_api.EnrollmentCleanup
import com.example.dpop.tool_spi.EnrollmentRef
import org.springframework.stereotype.Component

@Component
class QrOptInCleanup(
    private val qrOptInRepository: QrOptInRepository
) : EnrollmentCleanup {
    override val enrollmentType = QR_OPTIN_ENROLLMENT_TYPE

    override fun delete(enrollmentRef: EnrollmentRef) {
        qrOptInRepository.deleteById(enrollmentRef.id.toLong())
    }
}
