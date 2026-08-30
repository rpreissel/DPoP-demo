package com.example.dpop.auth_device.internal.authdevice

import com.example.dpop.tool_api.UserVerification

/**
 * Single-shot flow (docs/03-tool-architektur.md #3, the optional Flow pattern): the device proof
 * arrives already verified by `DeviceProofValidator`, so the only real decision left is whether
 * the presented key matches the account's enrolled one. No fields: there is only ever this one
 * shape, kept as a type only so it has the same `describe()` shape every other state has.
 */
internal data object AuthDeviceState {
    val step: String get() = "auth"

    /** No `stepData` needed: the proof comes from a signed device API call, not a form. */
    fun describe(): Pair<String, Map<String, Any?>?> = step to null
}

internal sealed interface AuthDeviceDecision {
    data class Complete(val userVerification: UserVerification) : AuthDeviceDecision
    data object WrongDevice : AuthDeviceDecision
}

internal object AuthDeviceFlow {

    fun decide(submittedThumbprint: String, enrolledThumbprint: String?, userVerification: UserVerification): AuthDeviceDecision =
        if (submittedThumbprint == enrolledThumbprint) {
            AuthDeviceDecision.Complete(userVerification)
        } else {
            AuthDeviceDecision.WrongDevice
        }

}
