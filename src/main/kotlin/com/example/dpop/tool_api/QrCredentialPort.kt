package com.example.dpop.tool_api

import com.example.dpop.tool_spi.EnrollmentRef

/**
 * Creates a QR-login opt-in for callers outside a Channel/ToolSession - the demo bootstrap
 * seeding (`demo_seed`). The sibling of [SmsCredentialPort] and [PasswordCredentialPort], so the
 * seed reaches the credential store without depending on `auth_qr` itself.
 *
 * Nothing is skipped by going this way: the opt-in is a pure marker with no secret, the same row
 * `enroll-qr` writes once the user agreed.
 */
interface QrCredentialPort {
    /** Stores a fresh opt-in marker and returns its reference. */
    fun optIn(): EnrollmentRef
}
