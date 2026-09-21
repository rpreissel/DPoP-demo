package com.example.dpop.auth_kobil.internal

import com.example.dpop.tool_api.UserVerification
import com.example.dpop.tool_spi.FactorType

/**
 * What one KOBIL run proves, derived from the access means the client used.
 *
 * POSSESSION is unconditional and, unusually for this system, genuinely server-verified: it rests
 * on an assertion this backend redeemed from KOBIL itself, not on anything the client asserted.
 * The second factor is the opposite kind of claim - how the user unlocked the local secret is
 * something no server can see (`tool_api.DeviceProofs`). It is counted anyway, under the same
 * reservation `auth_device` already carries; see [com.example.dpop.auth_kobil.AuthKobilDescriptor].
 *
 * One function for both tools on purpose: enrollment and authentication must price the same act
 * identically, and two copies would be free to drift.
 */
internal fun UserVerification.kobilFactorTypes(): Set<FactorType> = when (this) {
    UserVerification.PIN -> setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)
    UserVerification.BIOMETRIC -> setOf(FactorType.POSSESSION, FactorType.INHERENCE)
}
