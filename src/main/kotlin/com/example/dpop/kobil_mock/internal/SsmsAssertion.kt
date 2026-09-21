package com.example.dpop.kobil_mock.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * One assertion per successful SDK login, addressed by the one-time password handed to the app.
 *
 * The split between this row and the OTP is the whole mechanism: the app carries a reference, the
 * relying party's backend fetches the content. [redeemedAt] makes the reference single-use, so a
 * replayed OTP buys nothing.
 */
@Entity
@Table(schema = "kobil_mock", name = "ssms_assertion")
class SsmsAssertion(
    @Id
    @Column(name = "otp", nullable = false)
    var otp: String = "",

    @Column(name = "user_id", nullable = false)
    var userId: String = "",

    @Column(name = "device_id", nullable = false)
    var deviceId: String? = null,

    @Column(name = "risk_signals", nullable = false)
    var riskSignals: String = "",
) {
    @Column(name = "redeemed_at")
    var redeemedAt: Instant? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
}
