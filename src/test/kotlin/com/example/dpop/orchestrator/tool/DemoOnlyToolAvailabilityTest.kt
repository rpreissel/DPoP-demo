package com.example.dpop.orchestrator.tool

import com.example.dpop.auth_device.AuthDeviceDescriptor
import com.example.dpop.auth_device.EnrollDeviceDescriptor
import com.example.dpop.auth_kobil.AuthKobilDescriptor
import com.example.dpop.auth_kobil.EnrollKobilDescriptor
import com.example.dpop.id_eid.IdentEidDescriptor
import com.example.dpop.id_nect.IdentNectDescriptor
import com.example.dpop.auth_sms.AuthSmsDescriptor
import com.example.dpop.orchestrator.kernel.ChannelType
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.Optional

/**
 * Review 2026-09, M-1: a tool that declares itself demo-only ([com.example.dpop.tool_spi.ToolDescriptor.demoOnly])
 * is unavailable outside `demo.mode` - even where an operator setting explicitly enables it.
 */
class DemoOnlyToolAvailabilityTest : BehaviorSpec({

    val registry = ToolHandlerRegistry(listOf(AuthDeviceDescriptor, EnrollDeviceDescriptor, AuthSmsDescriptor))
    val repository = mockk<ToolAvailabilityRepository>()
    // An operator row that explicitly switches auth-device ON - it must not matter outside demo mode.
    every { repository.findById(any()) } answers {
        val key = firstArg<ToolAvailabilityKey>()
        Optional.of(ToolAvailability(key.toolId, key.channel).apply { enabled = true })
    }
    every { repository.findByChannel(any()) } returns emptyList()

    fun service(demoMode: Boolean) = ToolAvailabilityService(repository, registry, ToolDefaults(), demoMode)

    given("the tools whose level this instance cannot back (ADR-36)") {
        then("the device tools (claimed user verification), KOBIL, eID and Nect (simulated counterparts) declare themselves demo-only") {
            listOf(AuthDeviceDescriptor, EnrollDeviceDescriptor, AuthKobilDescriptor, EnrollKobilDescriptor, IdentEidDescriptor, IdentNectDescriptor)
                .forEach { (it.demoOnly != null) shouldBe true }
            AuthSmsDescriptor.demoOnly shouldBe null
        }
    }

    given("demo.mode=false") {
        val service = service(demoMode = false)
        then("demo-only tools are off in every channel, whatever the operator set; others are untouched") {
            ChannelType.entries.forEach { channel ->
                service.isEnabled("auth-device", channel) shouldBe false
                service.isEnabled("enroll-device", channel) shouldBe false
                service.isEnabled("auth-sms", channel) shouldBe true
                service.disabledToolIds(channel) shouldContainAll setOf("auth-device", "enroll-device")
                service.disabledToolIds(channel) shouldNotContain "auth-sms"
            }
        }
    }

    given("demo.mode=true") {
        val service = service(demoMode = true)
        then("demo-only tools follow the operator settings like any other") {
            service.isEnabled("auth-device", ChannelType.APP) shouldBe true
            service.disabledToolIds(ChannelType.APP) shouldNotContain "auth-device"
        }
    }
})
