package com.example.dpop.auth_device.internal.enrolldevice

import com.example.dpop.tool_api.DevicePublicKey
import com.example.dpop.tool_api.UserVerification
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class EnrollDeviceFlowTest : BehaviorSpec({

    given("an already-verified device proof") {
        val key = DevicePublicKey(kty = "EC", crv = "P-256", x = "x", y = "y", thumbprint = "thumb-a")

        then("it always enrolls, carrying the input through unchanged") {
            EnrollDeviceFlow.decide(EnrollDeviceInput(key, UserVerification.PIN, "channel-key", "My Phone")) shouldBe
                EnrollDeviceDecision.Enroll(key, UserVerification.PIN, "channel-key", "My Phone")
        }
    }

    given("describe()") {
        then("it names step enroll with no stepData") {
            EnrollDeviceState.describe() shouldBe ("enroll" to null)
        }
    }
})
