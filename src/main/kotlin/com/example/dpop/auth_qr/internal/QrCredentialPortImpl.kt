package com.example.dpop.auth_qr.internal

import com.example.dpop.auth_qr.QR_OPTIN_ENROLLMENT_TYPE
import com.example.dpop.tool_api.QrCredentialPort
import com.example.dpop.tool_spi.EnrollmentRef
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Implements [QrCredentialPort] - writes exactly the opt-in row
 * [EnrollQrToolHandler][com.example.dpop.auth_qr.internal.enrollqr.EnrollQrToolHandler] writes,
 * just without the ToolSession indirection.
 */
@Component
internal class QrCredentialPortImpl(
    private val qrOptInRepository: QrOptInRepository
) : QrCredentialPort {

    @Transactional
    override fun optIn(): EnrollmentRef =
        EnrollmentRef(type = QR_OPTIN_ENROLLMENT_TYPE, id = qrOptInRepository.save(QrOptIn()).id.toString())
}
