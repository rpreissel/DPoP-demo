package com.example.dpop.orchestrator.channel

import com.example.dpop.ext_personenverzeichnis.Personenverzeichnis
import com.example.dpop.ext_personenverzeichnis.Freischaltcodes
import com.example.dpop.ext_personenverzeichnis.strassenzeile
import com.example.dpop.tool_spi.DEMO_EMAIL
import org.springframework.stereotype.Component

/**
 * One demo persona as the frontend's persona picker receives it. Everything the register knows
 * comes from the register, live; only what the register cannot know is demo configuration here:
 * the e-mail address (our account data) and the eID card's restricted identifier (the card mock).
 * A person created on `/personenverzeichnis/` therefore shows up here right away, with those two left empty.
 */
data class DemoPerson(
    /** The Partnernummer - every person has one (ADR-34). */
    val personId: String,
    /** Only for a person insured with us, and even then possibly missing for a while. */
    val kvnr: String?,
    val name: String?,
    val vorname: String?,
    val email: String?,
    /** Street and house number in one line - as the eID card shows it, not as the register splits it. */
    val strasse: String?,
    val plz: String?,
    val ort: String?,
    val geburtsdatum: String?,
    /** Plaintext of the newest valid letter in the register's mailbox (ADR-31). */
    val fscCode: String?,
    val restrictedId: String?
)

private data class DemoPersonExtras(val email: String, val restrictedId: String)

/**
 * Keyed by Partnernummer, for the persons `demo_seed/V16__testdata.sql` seeds - the one key that
 * never changes (a KVNR does, now and then) and that a Partner has too. The first matches
 * [DEMO_EMAIL], so the single-value e-mail prefill of the lookup tools stays the first persona's.
 */
private val DEMO_PERSON_EXTRAS = mapOf(
    "P000000001" to DemoPersonExtras(DEMO_EMAIL, "T0103005K1D5S0V8T9W6UM2RTX"),
    "P000000002" to DemoPersonExtras("erika.beispiel@example.com", "T0208011X7Y2Q4M6B3LT0T28WJ"),
    "P000000003" to DemoPersonExtras("jane.doe@example.com", "T0304223A9B1N7K5D2PN1S44QE"),
    "P000000004" to DemoPersonExtras("paula.schulz@example.com", "T0405337C2D8R5H9F1QW3V61MZ"),
)

/** Reads the personas off the register - only [DisclosingDemoDisclosure] calls this. */
@Component
class DemoPersonas(
    private val register: Personenverzeichnis,
    private val freischaltcodes: Freischaltcodes,
) {
    fun all(): List<DemoPerson> = register.allePersonen().mapNotNull { person ->
        val personId = person.id ?: return@mapNotNull null
        val extras = DEMO_PERSON_EXTRAS[personId]
        DemoPerson(
            personId = personId,
            kvnr = person.kvnr,
            name = person.name,
            vorname = person.vorname,
            email = extras?.email,
            strasse = person.strassenzeile,
            plz = person.plz,
            ort = person.ort,
            geburtsdatum = person.geburtsdatum?.toString(),
            fscCode = freischaltcodes.juengsterGueltigerCode(personId),
            restrictedId = extras?.restrictedId
        )
    }
}
