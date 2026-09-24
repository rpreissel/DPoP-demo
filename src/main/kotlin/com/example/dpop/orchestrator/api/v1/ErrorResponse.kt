package com.example.dpop.orchestrator.api.v1

import com.example.dpop.texts.Text
import com.example.dpop.orchestrator.kernel.ErrorCode
import io.swagger.v3.oas.annotations.media.Schema

/**
 * The body of every error response (docs/07-betrieb.md #1).
 *
 * Before this type the shape existed only as `mapOf("error" to ..., "message" to ...)` inside the
 * exception handler, and the contract listed no error response at all - so the compatibility check
 * could not see a change to it. What people read is a [Text] reference, resolved by the client in
 * its language (docs/adr/ADR-033).
 */
@Schema(description = "Every error response has this shape. The HTTP status is fixed per `error` code.")
data class ErrorResponse(
    val error: ErrorCode,
    @field:Schema(
        description = "For people, not for program logic - branch on `error`. A text reference, resolved " +
            "against GET .../texts/{lang}. For INTERNAL_ERROR it is a fixed text; the details of an " +
            "unexpected failure stay in the server log."
    )
    val text: Text
)
