package com.example.dpop.orchestrator.channel

import com.example.dpop.ext_stammdaten.ExtStammdatenService
import com.example.dpop.ext_stammdaten.Freischaltcodes
import com.example.dpop.tool_spi.DEMO_EMAIL
import org.springframework.stereotype.Component

/**
 * One demo persona as the frontend's persona picker receives it. Everything the register knows
 * comes from the register, live; only what the register cannot know is demo configuration here:
 * the e-mail address (our account data) and the eID card's restricted identifier (the card mock).
 * A person created on `/ext/` therefore shows up here right away, with those two left empty.
 */
data class DemoPerson(
    val kvnr: String,
    val name: String?,
    val vorname: String?,
    val email: String?,
    val strasse: String?,
    val hausnummer: String?,
    val plz: String?,
    val ort: String?,
    val geburtsdatum: String?,
    /** Plaintext of the newest valid letter in the register's mailbox (ADR-31). */
    val fscCode: String?,
    val restrictedId: String?
)

private data class DemoPersonExtras(val email: String, val restrictedId: String)

/**
 * Keyed by KVNR, for the three persons `demo_seed/V16__testdata.sql` seeds. The first matches
 * [DEMO_EMAIL], so the single-value e-mail prefill of the lookup tools stays the first persona's.
 */
private val DEMO_PERSON_EXTRAS = mapOf(
    "A123456789" to DemoPersonExtras(DEMO_EMAIL, "T0103005K1D5S0V8T9W6UM2RTX"),
    "B987654321" to DemoPersonExtras("erika.beispiel@example.com", "T0208011X7Y2Q4M6B3LT0T28WJ"),
    "C111111111" to DemoPersonExtras("jane.doe@example.com", "T0304223A9B1N7K5D2PN1S44QE"),
)

/** Reads the personas off the register - only [DisclosingDemoDisclosure] calls this. */
@Component
class DemoPersonas(
    private val register: ExtStammdatenService,
    private val freischaltcodes: Freischaltcodes,
) {
    fun all(): List<DemoPerson> = register.allePersonen().mapNotNull { person ->
        val kvnr = person.kvnr ?: return@mapNotNull null
        val extras = DEMO_PERSON_EXTRAS[kvnr]
        DemoPerson(
            kvnr = kvnr,
            name = person.name,
            vorname = person.vorname,
            email = extras?.email,
            strasse = person.strasse,
            hausnummer = person.hausnummer,
            plz = person.plz,
            ort = person.ort,
            geburtsdatum = person.geburtsdatum?.toString(),
            fscCode = person.id?.let { freischaltcodes.juengsterGueltigerCode(it) },
            restrictedId = extras?.restrictedId
        )
    }
}
