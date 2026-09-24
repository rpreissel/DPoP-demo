package com.example.dpop.nect_mock.api.v1

import com.example.dpop.nect_mock.NectAttributes
import com.example.dpop.nect_mock.NectCaseView
import com.example.dpop.nect_mock.NectIdent
import com.example.dpop.nect_mock.NectProcedure
import com.example.dpop.nect_mock.NectRejectedException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

data class NectResultRequest(
    @field:Schema(example = "eid") val procedure: String,
    val attributes: NectAttributes,
    @field:Schema(example = "123456") val pin: String? = null,
    /** ePass only: the passport's expiry, which Nect checks itself and does not hand on. */
    val expiryDate: LocalDate? = null
)

data class NectFailRequest(@field:Schema(example = "Selfie passt nicht zum Passbild") val reason: String)

/** Where the jump page sends the browser next - back to the relying party. */
data class NectRedirect(val redirectUri: String)

/**
 * The jump page's face of the simulated Nect service (`/nect/`). Deliberately NOT under
 * `/orchestrator`: in reality the user is on Nect's own site here, and the relying party learns the
 * outcome only by redeeming the case. No login and no DPoP - this service knows nothing about ours.
 */
@RestController
@RequestMapping("/mock-nect")
@Tag(name = "Mock Nect", description = "Simulierter Identifizierungsdienst - kein Endpunkt dieser Anwendung, sondern das Fremdsystem")
class NectMockController(private val nect: NectIdent) {

    @GetMapping("cases/{caseId}")
    @Operation(summary = "Status eines Vorgangs und angefragte Attribute")
    fun case(@PathVariable caseId: UUID): ResponseEntity<NectCaseView> =
        nect.caseView(caseId)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    @PostMapping("cases/{caseId}/result")
    @Operation(
        summary = "Identifizierung abschließen",
        description = "procedure: eid, epass oder eudi. eID verlangt die PIN (Testwert 123456); ein abgelaufener Pass lässt den Vorgang scheitern. Weitergegeben wird nur, was der Vorgang angefragt hat."
    )
    fun result(@PathVariable caseId: UUID, @RequestBody request: NectResultRequest): NectRedirect =
        NectRedirect(nect.complete(caseId, NectProcedure.of(request.procedure), request.attributes, request.pin, request.expiryDate))

    @PostMapping("cases/{caseId}/failure")
    @Operation(summary = "Identifizierung scheitern lassen (Demo)")
    fun fail(@PathVariable caseId: UUID, @RequestBody request: NectFailRequest): NectRedirect =
        NectRedirect(nect.fail(caseId, request.reason))

    @PostMapping("cases/{caseId}/cancellation")
    @Operation(summary = "Identifizierung abbrechen")
    fun cancel(@PathVariable caseId: UUID): NectRedirect = NectRedirect(nect.cancel(caseId))

    /** Nect answers for itself; its refusals are not this application's error contract. */
    @ExceptionHandler(NectRejectedException::class)
    fun rejected(exception: NectRejectedException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(mapOf("error" to exception.message))
}
