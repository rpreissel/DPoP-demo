package com.example.dpop.auth_device.internal.enrolldevice

import com.example.dpop.tool_api.DevicePublicKey
import com.example.dpop.tool_api.UserVerification

/**
 * Single-shot flow (docs/03-tool-architektur.md #3, the optional Flow pattern): the device proof
 * arrives already verified by `DeviceProofValidator` at the call site, so there is no missing-field
 * or invalid-input case here to decide between - [decide] always enrolls. No fields: there is
 * only ever this one shape, kept as a type only so it has the same `describe()` shape every
 * other state has.
 */
internal data object EnrollDeviceState {
    val step: String get() = "enroll"

    /** No `stepData` needed: the fields come from a signed device API call, not a form the client fills incrementally. */
    fun describe(): Pair<String, Map<String, Any?>?> = step to null
}

internal data class EnrollDeviceInput(val devicePublicKey: DevicePublicKey, val userVerification: UserVerification, val deviceBindingKeyRef: String, val label: String?)

internal sealed interface EnrollDeviceDecision {
    data class Enroll(val devicePublicKey: DevicePublicKey, val userVerification: UserVerification, val deviceBindingKeyRef: String, val label: String?) : EnrollDeviceDecision
}

internal object EnrollDeviceFlow {

    fun decide(input: EnrollDeviceInput): EnrollDeviceDecision.Enroll =
        EnrollDeviceDecision.Enroll(input.devicePublicKey, input.userVerification, input.deviceBindingKeyRef, input.label)

}
