package com.example.dpop.auth_qr.api.v1

import com.example.dpop.tool_spi.StepData
import com.example.dpop.tool_spi.StepDataTypes
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonTypeName
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * What a QR pairing step shows. The browser (`waitForApp`) shows the [pairingCode] the app scans or
 * types. The app, right after approving (`showCode`), shows the [confirmationCode] the user types
 * into the browser - once, in that one response; it is stored only as a hash (review 2026-09, M-2).
 *
 * Declared in `auth_qr` because only `auth_qr` produces it (see [StepDataTypes]).
 */
@JsonTypeName("qr-pairing")
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A QR pairing in progress: the pairing code (browser), or the confirmation code to type into the browser (app, once).")
data class QrPairingStep(
    @field:Schema(example = "7F3K92QL")
    val pairingCode: String? = null,
    @field:Schema(example = "482913")
    val confirmationCode: String? = null
) : StepData

/** The browser's PATCH on `auth-qr`/`auth-qr-lookup`: empty while polling, then the confirmation code. */
data class QrConfirmationCodeRequest(
    @field:Schema(example = "482913") val confirmationCode: String? = null
)

@Configuration
class QrStepDataTypes {

    @Bean
    fun qrStepDataShapes() = StepDataTypes { listOf(QrPairingStep::class) }
}
