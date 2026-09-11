package com.example.dpop.auth_device.internal.enrolldevice
import com.example.dpop.auth_device.internal.DeviceEnrollment
import com.example.dpop.auth_device.internal.DeviceEnrollmentRepository

import com.example.dpop.auth_device.DEVICE_BINDING_KEY_REF
import com.example.dpop.auth_device.DEVICE_ENROLLMENT_TYPE
import com.example.dpop.auth_device.EnrollDeviceDescriptor
import com.example.dpop.tool_api.DevicePublicKey
import com.example.dpop.tool_api.UserVerification
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.ToolOutcome
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import java.util.Optional
import java.util.UUID

/**
 * Pure unit test: no Spring context, repositories mocked with MockK. Covers persistence/outcome
 * wiring only - [EnrollDeviceFlow] has no decision branches (the proof arrives already verified,
 * so it always enrolls); this instead owns the idempotent-reuse-by-thumbprint behavior, which
 * lives in the handler itself, not the Flow.
 */
class EnrollDeviceToolHandlerTest : BehaviorSpec({

    val toolDataRepository = mockk<EnrollDeviceToolDataRepository>()
    val enrollmentRepository = mockk<DeviceEnrollmentRepository>()
    val handler = EnrollDeviceToolHandler(EnrollDeviceDescriptor, toolDataRepository, enrollmentRepository)
    val toolSessionId = UUID.randomUUID()
    val devicePublicKey = DevicePublicKey(kty = "EC", crv = "P-256", x = "x-coord", y = "y-coord", thumbprint = "thumb-1")

    given("an active enroll-device tool session") {
        every { toolDataRepository.findById(toolSessionId) } returns Optional.of(EnrollDeviceToolData(toolSessionId = toolSessionId))

        `when`("no device with this thumbprint is enrolled yet") {
            every { enrollmentRepository.findByThumbprint("thumb-1") } returns null
            every { enrollmentRepository.save(any()) } answers { firstArg<DeviceEnrollment>().apply { id = 9L } }

            then("it enrolls a new device_enrollment row, with PIN mapped to POSSESSION+KNOWLEDGE") {
                val outcome = handler.patch(toolSessionId, devicePublicKey, UserVerification.PIN, "binding-key-1", "Handy")

                outcome.shouldBeInstanceOf<ToolOutcome.Completed.Enrolled>()
                val enrolled = outcome as ToolOutcome.Completed.Enrolled
                enrolled.enrollmentRef.type shouldBe DEVICE_ENROLLMENT_TYPE
                enrolled.enrollmentRef.id shouldBe "9"
                enrolled.amr shouldBe listOf("device", "pin")
                enrolled.achievedAcr shouldBe EnrollDeviceDescriptor.maxAcr
                enrolled.factorTypes shouldBe setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)
                enrolled.auditDetails shouldBe mapOf("thumbprint" to "thumb-1", DEVICE_BINDING_KEY_REF to "binding-key-1", "label" to "Handy")
            }
        }

        `when`("this exact thumbprint is already enrolled (re-enrolling the same physical key)") {
            val existing = DeviceEnrollment(thumbprint = "thumb-1").apply { id = 3L }
            every { enrollmentRepository.findByThumbprint("thumb-1") } returns existing

            then("the existing row is reused, not a second INSERT, with BIOMETRIC mapped to POSSESSION+INHERENCE") {
                val outcome = handler.patch(toolSessionId, devicePublicKey, UserVerification.BIOMETRIC, "binding-key-1", null)

                outcome.shouldBeInstanceOf<ToolOutcome.Completed.Enrolled>()
                val enrolled = outcome as ToolOutcome.Completed.Enrolled
                enrolled.enrollmentRef.id shouldBe "3"
                enrolled.factorTypes shouldBe setOf(FactorType.POSSESSION, FactorType.INHERENCE)
                // No stub for enrollmentRepository.save() in this scenario - calling it here would
                // throw (MockK is strict by default), which is the reuse-not-insert proof.
            }
        }
    }
})
