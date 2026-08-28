package com.example.dpop.orchestrator.tool

import com.example.dpop.tool_spi.ToolDescriptor
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.maps.shouldContainExactly
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * Pins the start step of every registered tool against the real Spring-collected catalog.
 *
 * This used to be a hand-maintained `toolId -> step` map inside the orchestrator
 * (`ToolSteps`); [ToolDescriptor.startStep] derives it from `role` instead. The expectations
 * below are the same table, but now as an assertion of intent rather than production logic - a
 * tool missing here fails loudly ("catalog has tools not covered") instead of silently
 * defaulting to a wrong-but-plausible step, which is what had happened to `auth-email-lookup`.
 *
 * Keep in sync with the handlers' own first `InProgress(nextStep = ...)`: that literal and this
 * value are the same contract seen from both ends.
 */
@SpringBootTest
@ActiveProfiles("test")
class ToolCatalogStartStepTest(toolRegistry: ToolHandlerRegistry) : BehaviorSpec({

    val expectedStartSteps = mapOf(
        "ident-fsc" to "input",
        "ident-eid" to "input",
        "enroll-sms" to "enroll",
        "auth-sms" to "auth",
        "auth-sms-lookup" to "auth",
        "enroll-password" to "enroll",
        "auth-password" to "auth",
        "auth-password-lookup" to "auth",
        "enroll-email" to "enroll",
        "auth-email" to "auth",
        "auth-email-lookup" to "auth",
        "enroll-device" to "enroll",
        "auth-device" to "auth"
    )

    given("the real Spring-collected tool catalog") {
        then("every registered tool starts on its documented step") {
            val actual = toolRegistry.descriptors().associate { it.toolId to it.startStep }
            actual shouldContainExactly expectedStartSteps
        }
    }
})
