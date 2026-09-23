package com.example.dpop.auth_qr.api.v1

import com.example.dpop.tool_spi.StepData
import com.example.dpop.tool_spi.StepDataTypes
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonTypeName
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * What a QR pairing step shows: the code the other device must scan or type, and - once the
 * pairing is under way - the verification code both sides compare.
 *
 * Declared in `auth_qr` because only `auth_qr` produces it (see [StepDataTypes]).
 */
@JsonTypeName("qr-pairing")
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A QR pairing in progress: the pairing code, and the verification code once known.")
data class QrPairingStep(
    @field:Schema(example = "7F3K-92QL")
    val pairingCode: String?,
    @field:Schema(example = "4711")
    val verificationCode: String? = null
) : StepData

@Configuration
class QrStepDataTypes {

    @Bean
    fun qrStepDataShapes() = StepDataTypes { listOf(QrPairingStep::class) }
}
