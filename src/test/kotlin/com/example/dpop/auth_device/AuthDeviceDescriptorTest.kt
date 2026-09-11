package com.example.dpop.auth_device

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * [AuthDeviceDescriptor.matchesCaller] is the one place a null `callerBindingKeyRef` (a WEB
 * channel, which has no device, docs/02-domaenenmodell.md Abschnitt 1) needs to behave correctly:
 * it must never match a real, device-enrolled instance.
 */
class AuthDeviceDescriptorTest : BehaviorSpec({

    given("a device instance enrolled under a real bindingKeyRef") {
        val details = mapOf(DEVICE_BINDING_KEY_REF to "device-key-1")

        `when`("the caller presents the same bindingKeyRef") {
            then("it matches") {
                AuthDeviceDescriptor.matchesCaller(details, "device-key-1") shouldBe true
            }
        }
        `when`("the caller presents a different bindingKeyRef") {
            then("it does not match") {
                AuthDeviceDescriptor.matchesCaller(details, "some-other-device-key") shouldBe false
            }
        }
        `when`("the caller has no bindingKeyRef at all (a WEB channel)") {
            then("it does not match - a null caller never matches a real device instance") {
                AuthDeviceDescriptor.matchesCaller(details, null) shouldBe false
            }
        }
    }
})
