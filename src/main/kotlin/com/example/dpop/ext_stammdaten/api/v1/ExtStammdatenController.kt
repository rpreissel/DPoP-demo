package com.example.dpop.ext_stammdaten.api.v1

import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.http.HttpHeaders
import com.example.dpop.texts.TextBundle
import com.example.dpop.texts.Text
import com.example.dpop.ext_stammdaten.BriefView
import com.example.dpop.ext_stammdaten.ExtStammdatenService
import com.example.dpop.ext_stammdaten.Freischaltcodes
import com.example.dpop.ext_stammdaten.FreischaltcodeView
import com.example.dpop.ext_stammdaten.PersonData
import com.example.dpop.ext_stammdaten.PersonRejectedException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class FreischaltcodeAusstellenRequest(val gueltigBis: Instant)

/**
 * The register's own management face, used by the `/ext/` page - a stand-in for the operator UI
 * of the real person register. Like `/mock-kobil` it is deliberately NOT under `/orchestrator`:
 * it is the foreign system, not this application, and it knows nothing about channels, journeys
 * or accounts. No login either - our admin rights do not reach into someone else's system.
 *
 * Persons cannot be deleted here: accounts refer to a person id, and a register that forgets a
 * person is a case of its own, not a side effect of this demo page.
 */
@RestController
@RequestMapping("/mock-stammdaten")
@Tag(name = "Mock Personenregister", description = "Simuliertes Personenregister - kein Endpunkt dieser Anwendung, sondern das Fremdsystem")
class ExtStammdatenController(
    private val register: ExtStammdatenService,
    private val freischaltcodes: Freischaltcodes,
) {

    @GetMapping("personen")
    @Operation(summary = "Alle Personen im Register")
    fun personen(): List<PersonData> = register.allePersonen()

    @PostMapping("personen")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Person anlegen", description = "Die KVNR muss ein Buchstabe und neun Ziffern sein und darf noch nicht vergeben sein.")
    fun anlegen(@RequestBody person: PersonData): PersonData = register.anlegen(person)

    @PutMapping("personen/{personId}")
    @Operation(summary = "Stammdaten einer Person ändern", description = "Die KVNR bleibt unverändert.")
    fun aendern(@PathVariable personId: Long, @RequestBody person: PersonData): ResponseEntity<PersonData> =
        register.aendern(personId, person)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    @GetMapping("personen/{personId}/freischaltcodes")
    @Operation(summary = "Freischaltcodes einer Person", description = "Ohne Klartext - den trägt nur der Brief.")
    fun freischaltcodes(@PathVariable personId: Long): List<FreischaltcodeView> = freischaltcodes.fuerPerson(personId)

    @PostMapping("personen/{personId}/freischaltcodes")
    @Operation(summary = "Freischaltcode ausstellen", description = "Antwortet mit dem Brief, der den Klartext trägt.")
    fun ausstellen(@PathVariable personId: Long, @RequestBody request: FreischaltcodeAusstellenRequest): ResponseEntity<BriefView> {
        if (register.findPersonById(personId) == null) return ResponseEntity.notFound().build()
        return ResponseEntity.status(HttpStatus.CREATED).body(freischaltcodes.ausstellen(personId, request.gueltigBis))
    }

    @DeleteMapping("freischaltcodes/{freischaltcodeId}")
    @Operation(summary = "Freischaltcode widerrufen")
    fun widerrufen(@PathVariable freischaltcodeId: Long): ResponseEntity<Void> =
        if (freischaltcodes.widerrufen(freischaltcodeId)) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()

    @GetMapping("briefe")
    @Operation(summary = "Briefkasten", description = "Alle verschickten Briefe, neueste zuerst.")
    fun briefe(): List<BriefView> = freischaltcodes.briefkasten()

    /** The register answers for itself; its refusals are not this application's error contract. */
    @ExceptionHandler(PersonRejectedException::class)
    fun rejected(exception: PersonRejectedException): ResponseEntity<Map<String, Text>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to exception.text))

    /**
     * This service's own texts in [lang], for its own page - a foreign system brings its wordings
     * along (docs/adr/ADR-033). ETag/If-None-Match: 304 while the client's copy is current.
     */
    @GetMapping("texts/{lang}")
    @Operation(summary = "Texte des Dienstes in einer Sprache (mit ETag)")
    fun texts(
        @PathVariable lang: String,
        @RequestHeader(HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?
    ): ResponseEntity<Map<String, String>> = TEXTS.respond(lang, ifNoneMatch)

    private companion object {
        val TEXTS = TextBundle("register")
    }
}
