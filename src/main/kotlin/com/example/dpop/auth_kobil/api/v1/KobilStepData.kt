package com.example.dpop.auth_kobil.api.v1

import com.example.dpop.tool_spi.StepData
import com.example.dpop.tool_spi.StepDataTypes
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonTypeName
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The step shapes `auth_kobil` produces - declared here, in the module that produces them, not in
 * a shared list somewhere else (see [StepDataTypes]).
 *
 * Both carry `tenantId`/`kobilUserId` because the app hands them straight to the KOBIL SDK; they
 * identify the user at the provider, not with us.
 */

/** The app must unlock the stored PIN before it can be used - these are the ways it may. */
@JsonTypeName("kobil-unlock")
@Schema(description = "The app must unlock the backend-held PIN; these are the accepted ways.")
data class KobilUnlockStep(
    @field:Schema(example = "[\"BIOMETRIC\", \"PIN\"]")
    val unlockOptions: List<String>,
    val tenantId: String,
    val kobilUserId: String
) : StepData

/** The SDK produced an OTP and it is now expected back. */
@JsonTypeName("kobil-otp")
@Schema(description = "Waiting for the one-time password the KOBIL SDK produced.")
@JsonInclude(JsonInclude.Include.NON_NULL)
data class KobilOtpStep(
    @field:Schema(example = "[\"otp\"]")
    val missingFields: List<String>,
    val tenantId: String,
    val kobilUserId: String,
    /**
     * The released PIN, handed to the SDK for this one call (ADR-21/ADR-22). Part of the step, not
     * of the demo block: the app needs it to work at all, in every deployment.
     */
    val kobilPin: String? = null
) : StepData

/**
 * Setting up a KOBIL binding: everything the SDK's activation call needs.
 *
 * `pin` and `unlockSecret` are handed over because the SDK takes them - the user never sees either
 * (ADR-21/ADR-22). They are part of the step, not of the demo block.
 */
@JsonTypeName("kobil-activation")
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "What the KOBIL SDK needs to activate this device.")
data class KobilActivationStep(
    val missingFields: List<String>,
    val tenantId: String,
    val kobilUserId: String,
    val activationCode: String,
    val pin: String,
    val unlockSecret: String? = null
) : StepData

@Configuration
class KobilStepDataTypes {

    @Bean
    fun kobilStepDataShapes() = StepDataTypes {
        listOf(KobilUnlockStep::class, KobilOtpStep::class, KobilActivationStep::class)
    }
}
