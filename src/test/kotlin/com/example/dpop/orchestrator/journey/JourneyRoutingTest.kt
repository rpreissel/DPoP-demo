package com.example.dpop.orchestrator.journey

import com.example.dpop.auth_email.EnrollEmailDescriptor
import com.example.dpop.auth_sms.EnrollSmsDescriptor
import com.example.dpop.orchestrator.domain.journey.state.ManageAuthMethodsState
import com.example.dpop.orchestrator.domain.journey.state.Offer
import com.example.dpop.orchestrator.domain.ChannelType
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.tool.ToolAvailabilityService
import com.example.dpop.orchestrator.tool.ToolHandlerRegistry
import com.example.dpop.texts.Text
import com.example.dpop.tool_api.Next
import com.example.dpop.tool_spi.ToolId
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk

/**
 * A single candidate is started on its own - unless activating it completes it at once
 * (ToolDescriptor.completesOnActivation). Found in the web channel: "Neues Anmeldeverfahren
 * hinzufügen" left only enroll-email, which was started, completed, and added a method the user
 * never saw or chose.
 */
class JourneyRoutingTest : BehaviorSpec({

    val availability = mockk<ToolAvailabilityService> {
        every { disabledToolIds(any()) } returns emptySet()
        every { ordered(any(), any()) } answers { secondArg<Collection<ToolId>>().toList() }
    }
    val routing = JourneyRouting(ToolHandlerRegistry(listOf(EnrollEmailDescriptor, EnrollSmsDescriptor)), availability)
    val webChannel = ChannelSession(channel = ChannelType.KEYCLOAK).apply {
        availableClientTools = mutableSetOf("enroll-email", "enroll-sms")
    }

    fun adding(vararg tools: String) = ManageAuthMethodsState.Enrolling(Offer(tools.map { ToolId(it) }))

    given("adding a sign-in method with only enroll-email left") {
        val step = routing.stepFor(adding("enroll-email"), webChannel)

        then("the selection page opens instead of starting it, naming why there is just this one") {
            step.next shouldBe Next.orchestrator("enrollment", "selectMethod")
            val select = step.stepData.shouldBeInstanceOf<SelectMethodStep>()
            select.options shouldBe listOf("enroll-email")
            select.title shouldBe Text("Neues Anmeldeverfahren hinzufügen")
            select.description shouldBe Text(
                "Nur dieses Verfahren steht hier noch zur Wahl. {grund}",
                "grund" to EnrollEmailDescriptor.completesOnActivation,
            )
        }
    }

    given("adding a sign-in method with only enroll-sms left") {
        then("it still starts on its own - it has a step of its own to show") {
            routing.stepFor(adding("enroll-sms"), webChannel).next shouldBe Next.tool("enroll-sms", EnrollSmsDescriptor.startStep)
        }
    }

    given("adding a sign-in method with both left") {
        then("the ordinary selection, with the state's own description") {
            val select = routing.stepFor(adding("enroll-email", "enroll-sms"), webChannel).stepData.shouldBeInstanceOf<SelectMethodStep>()
            select.options shouldBe listOf("enroll-email", "enroll-sms")
            select.description shouldBe adding().selectionDescription
        }
    }
})
