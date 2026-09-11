package com.example.dpop.auth_device.internal.authdevice
import com.example.dpop.auth_device.internal.DeviceEnrollment
import com.example.dpop.auth_device.internal.DeviceEnrollmentRepository

import com.example.dpop.auth_device.AuthDeviceDescriptor
import com.example.dpop.auth_device.DEVICE_ENROLLMENT_TYPE
import com.example.dpop.tool_api.DevicePublicKey
import com.example.dpop.tool_api.UserVerification
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.ToolOutcome
import com.example.dpop.tool_spi.UnresolvableReferenceException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import java.util.Optional
import java.util.UUID

/**
 * Pure unit test: no Spring context, repositories mocked with MockK. Covers persistence/outcome
 * wiring only - the match decision is covered by [AuthDeviceFlowTest].
 */
class AuthDeviceToolHandlerTest : BehaviorSpec({

    val toolDataRepository = mockk<AuthDeviceToolDataRepository>()
    val enrollmentRepository = mockk<DeviceEnrollmentRepository>()
    val handler = AuthDeviceToolHandler(AuthDeviceDescriptor, toolDataRepository, enrollmentRepository)
    val toolSessionId = UUID.randomUUID()

    given("start()") {
        `when`("the enrollment reference has the wrong type") {
            then("it throws UnresolvableReferenceException") {
                shouldThrow<UnresolvableReferenceException> {
                    handler.start(toolSessionId, EnrollmentRef("auth_password_enrollment", "1"))
                }
            }
        }

        `when`("the enrollment reference id is not numeric") {
            then("it throws UnresolvableReferenceException") {
                shouldThrow<UnresolvableReferenceException> {
                    handler.start(toolSessionId, EnrollmentRef(DEVICE_ENROLLMENT_TYPE, "not-a-number"))
                }
            }
        }

        `when`("the referenced device_enrollment row does not exist") {
            every { enrollmentRepository.findById(1L) } returns Optional.empty()

            then("it throws UnresolvableReferenceException") {
                shouldThrow<UnresolvableReferenceException> {
                    handler.start(toolSessionId, EnrollmentRef(DEVICE_ENROLLMENT_TYPE, "1"))
                }
            }
        }

        `when`("the referenced device_enrollment row exists") {
            every { enrollmentRepository.findById(1L) } returns Optional.of(DeviceEnrollment(thumbprint = "thumb-1").apply { id = 1L })
            every { toolDataRepository.save(any()) } answers { firstArg() }

            then("it asks for the device proof at step auth") {
                val outcome = handler.start(toolSessionId, EnrollmentRef(DEVICE_ENROLLMENT_TYPE, "1"))

                outcome.shouldBeInstanceOf<ToolOutcome.InProgress>()
                (outcome as ToolOutcome.InProgress).nextStep shouldBe "auth"
            }
        }
    }

    given("an active auth-device tool session bound to an enrollment") {
        val data = AuthDeviceToolData(toolSessionId = toolSessionId, enrollmentRefType = DEVICE_ENROLLMENT_TYPE, enrollmentRefId = "1")
        every { toolDataRepository.findById(toolSessionId) } returns Optional.of(data)
        every { enrollmentRepository.findById(1L) } returns Optional.of(DeviceEnrollment(thumbprint = "thumb-1").apply { id = 1L })

        `when`("the presented device key's thumbprint matches the enrolled one") {
            then("it authenticates at the descriptor's own maxAcr, BIOMETRIC mapped to POSSESSION+INHERENCE") {
                val devicePublicKey = DevicePublicKey(kty = "EC", crv = "P-256", x = "x-coord", y = "y-coord", thumbprint = "thumb-1")
                val outcome = handler.patch(toolSessionId, devicePublicKey, UserVerification.BIOMETRIC)

                outcome.shouldBeInstanceOf<ToolOutcome.Completed.Authenticated>()
                val authenticated = outcome as ToolOutcome.Completed.Authenticated
                authenticated.amr shouldBe listOf("device", "biometric")
                authenticated.achievedAcr shouldBe AuthDeviceDescriptor.maxAcr
            }
        }

        `when`("the presented device key's thumbprint doesn't match the enrolled one") {
            then("it fails") {
                val devicePublicKey = DevicePublicKey(kty = "EC", crv = "P-256", x = "other-x", y = "other-y", thumbprint = "thumb-other")
                handler.patch(toolSessionId, devicePublicKey, UserVerification.PIN) shouldBe ToolOutcome.Failed("Geraet nicht erkannt")
            }
        }
    }
})
