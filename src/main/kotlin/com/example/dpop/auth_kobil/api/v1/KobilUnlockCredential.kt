package com.example.dpop.auth_kobil.api.v1

import com.example.dpop.tool_api.UserVerification
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * What the app presents to get the PIN released: either the locally stored secret (which its
 * biometric prompt guards) or the account password.
 *
 * A sealed one-of rather than two nullable fields on one request: "both" and "neither" are then
 * not constructible, the handler's `when` is exhaustive, and the access means a run reports
 * follows from the type instead of from a flag someone has to keep in step with it.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = KobilUnlockCredential.BiometricUnlock::class, name = "biometric"),
    JsonSubTypes.Type(value = KobilUnlockCredential.PasswordUnlock::class, name = "password"),
)
sealed interface KobilUnlockCredential {

    /**
     * The access means this unlock amounts to. `BIOMETRIC` and `PIN` are the vocabulary
     * `auth_device` already established for exactly this question, reused rather than restated:
     * a second set of names for the same idea would have to be kept aligned by hand.
     *
     * The account password maps to `PIN`, i.e. to knowledge, and deliberately NOT to an amr entry
     * of its own: it is the access means of THIS credential, not a second login. An amr entry
     * named `password` would make `JourneyRecorder` attach the account's real password
     * enrollment to a KOBIL run, and the password would count twice.
     */
    val userVerification: UserVerification

    data class BiometricUnlock(val unlockSecret: String) : KobilUnlockCredential {
        override val userVerification get() = UserVerification.BIOMETRIC
    }

    data class PasswordUnlock(val password: String) : KobilUnlockCredential {
        override val userVerification get() = UserVerification.PIN
    }
}

/** Body of `POST .../auth-kobil/pin-releases`. */
data class KobilPinReleaseRequest(val unlock: KobilUnlockCredential)
