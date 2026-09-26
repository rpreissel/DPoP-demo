package com.example.dpop.ext_personenverzeichnis

import com.example.dpop.texts.Text
import com.example.dpop.ext_personenverzeichnis.internal.MrzName
import com.example.dpop.ext_personenverzeichnis.internal.PersonRepository
import com.example.dpop.ext_personenverzeichnis.internal.Person
import com.example.dpop.tool_api.ClaimedIdentity
import com.example.dpop.tool_api.Kvnr
import com.example.dpop.tool_spi.Partnernr
import com.example.dpop.tool_api.PersonChanged
import com.example.dpop.tool_api.Versnr
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_api.PersonDirectory
import com.example.dpop.tool_api.PersonMasterData
import com.example.dpop.tool_api.PersonRecord
import com.example.dpop.tool_api.normalizeKvnr
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.LocalDate

/** Raised when the register refuses a change; its refusals are not this application's error contract. */
class PersonRejectedException(val text: Text) : RuntimeException(text.template)

@Service
class Personenverzeichnis(
    private val personRepository: PersonRepository,
    private val events: ApplicationEventPublisher
) : PersonDirectory, PersonMasterData {

    private val random = SecureRandom()

    override fun findPersonIdByKvnr(kvnr: String): String? =
        personRepository.findByKvnr(normalizeKvnr(kvnr))?.id

    override fun findPersonIdByPartnernr(partnernr: String): String? =
        Partnernr.ofOrNull(partnernr)?.let { personRepository.findByIdOrNull(it.value) }?.id

    override fun versnrOf(personId: String): String? = personRepository.findByIdOrNull(personId)?.versnr

    override fun matchesStammdaten(personId: String, claimed: ClaimedIdentity): Boolean {
        val person = personRepository.findByIdOrNull(personId) ?: return false
        // null = the attestation didn't include the attribute; it is not compared. Names compare
        // in MRZ form (MrzName): a document reads them differently than the register writes them.
        // The street line compares against the register's own two fields, joined.
        return namesMatch(person, claimed.name, claimed.vorname) &&
            (claimed.geburtsdatum == null || person.geburtsdatum == claimed.geburtsdatum) &&
            (claimed.strasse == null || MrzName.of(person.toPersonData().strassenzeile.orEmpty()) == MrzName.of(claimed.strasse)) &&
            (claimed.plz == null || person.plz == claimed.plz?.trim()) &&
            (claimed.ort == null || MrzName.of(person.ort.orEmpty()) == MrzName.of(claimed.ort))
    }

    override fun matchesPersonalien(personId: String, name: String, vorname: String, geburtsdatum: LocalDate): Boolean {
        val person = personRepository.findByIdOrNull(personId) ?: return false
        return namesMatch(person, name, vorname) && person.geburtsdatum == geburtsdatum
    }

    /** Both names present: the whole MRZ name field, cut like a passport's; one alone: that one. */
    private fun namesMatch(person: Person, name: String?, vorname: String?): Boolean = when {
        name != null && vorname != null ->
            MrzName.sameName(name, vorname, person.name.orEmpty(), person.vorname.orEmpty())
        else ->
            (name == null || MrzName.of(name) == MrzName.of(person.name.orEmpty())) &&
                (vorname == null || MrzName.of(vorname) == MrzName.of(person.vorname.orEmpty()))
    }

    override fun displayName(personId: String): String? {
        val person = personRepository.findByIdOrNull(personId) ?: return null
        return listOfNotNull(person.vorname, person.name).joinToString(" ").ifBlank { null }
    }

    fun findPersonByKvnr(kvnr: String): PersonData? =
        personRepository.findByKvnr(normalizeKvnr(kvnr))?.toPersonData()

    fun findPersonById(personId: String): PersonData? =
        personRepository.findByIdOrNull(personId)?.toPersonData()

    override fun masterDataOf(personId: String): PersonRecord? =
        findPersonById(personId)?.let {
            PersonRecord(
                kvnr = it.kvnr, name = it.name, vorname = it.vorname, geburtsdatum = it.geburtsdatum,
                strasse = it.strassenzeile, plz = it.plz, ort = it.ort, versnr = it.versnr
            )
        }

    // ------------------------------------------------------------------ register management (/personenverzeichnis/)

    @Transactional(readOnly = true)
    fun allePersonen(): List<PersonData> = personRepository.findAll().sortedBy { it.id }.map { it.toPersonData() }

    /**
     * Creates a person - a Partner (neither number) or a Versicherter (Versicherungsnummer, KVNR
     * usually too, ADR-34).
     *
     * @throws PersonRejectedException for a malformed or already registered KVNR or
     *   Versicherungsnummer, or a KVNR without a Versicherungsnummer.
     */
    @Transactional
    fun anlegen(input: PersonData): PersonData {
        val (kvnr, versnr) = validNumbers(input)
        requireKvnrFree(kvnr)
        requireVersnrFree(versnr)
        return personRepository.save(Person(id = neuePartnernr(), kvnr = kvnr, versnr = versnr).apply { applyFrom(input) }).toPersonData()
    }

    /**
     * Changes a person - KVNR and Versicherungsnummer included; the id (Partnernummer) stays. What
     * actually changed goes out as [PersonChanged] (ADR-34), in this transaction: accounts bound to
     * the person follow it, Keycloak too. Nothing changed, nothing announced.
     *
     * @return null when no such person exists.
     * @throws PersonRejectedException for a malformed or already used KVNR or Versicherungsnummer,
     *   or a KVNR without a Versicherungsnummer.
     */
    @Transactional
    fun aendern(personId: String, input: PersonData): PersonData? {
        val person = personRepository.findByIdOrNull(personId) ?: return null
        val (kvnr, versnr) = validNumbers(input)
        if (kvnr != person.kvnr) requireKvnrFree(kvnr)
        if (versnr != person.versnr) requireVersnrFree(versnr)

        val before = person.toPersonData()
        person.kvnr = kvnr
        person.versnr = versnr
        person.applyFrom(input)
        val after = person.toPersonData()

        val changed = changedAttributes(before, after)
        if (changed.isNotEmpty()) events.publishEvent(PersonChanged(personId, changed, kvnr, versnr))
        return after
    }

    /**
     * KVNR and Versicherungsnummer, each empty = none. A KVNR only exists for a person insured with
     * us (ADR-34) - the other way round it may be missing for a while, KVNRs change now and then.
     */
    private fun validNumbers(input: PersonData): Pair<String?, String?> {
        val kvnr = input.kvnr?.takeIf { it.isNotBlank() }?.let {
            Kvnr.ofOrNull(it)?.value ?: throw PersonRejectedException(Text("KVNR muss ein Buchstabe und neun Ziffern sein"))
        }
        val versnr = input.versnr?.takeIf { it.isNotBlank() }?.let {
            Versnr.ofOrNull(it)?.value ?: throw PersonRejectedException(Text("Die Versicherungsnummer muss aus acht Ziffern bestehen"))
        }
        if (kvnr != null && versnr == null) {
            throw PersonRejectedException(Text("Eine KVNR gibt es nur zusammen mit einer Versicherungsnummer"))
        }
        return kvnr to versnr
    }

    /** A fresh Partnernummer - random, so it gives away neither order nor count of the persons. */
    private fun neuePartnernr(): String =
        generateSequence { Partnernr.ofDigits(random.nextInt(1_000_000_000)).value }
            .first { !personRepository.existsById(it) }

    private fun requireKvnrFree(kvnr: String?) {
        if (kvnr != null && personRepository.findByKvnr(kvnr) != null) {
            throw PersonRejectedException(Text("KVNR {kvnr} ist bereits registriert", "kvnr" to kvnr))
        }
    }

    private fun requireVersnrFree(versnr: String?) {
        if (versnr != null && personRepository.findByVersnr(versnr) != null) {
            throw PersonRejectedException(Text("Versicherungsnummer {versnr} ist bereits vergeben", "versnr" to versnr))
        }
    }

    /** The kinds of attributes that differ - the street line (street + number) is one kind. */
    private fun changedAttributes(before: PersonData, after: PersonData): Set<AttributeType> = buildSet {
        if (before.kvnr != after.kvnr) add(AttributeType.KVNR)
        if (before.versnr != after.versnr) add(AttributeType.INSURANCE_NUMBER)
        if (before.name != after.name) add(AttributeType.FAMILY_NAME)
        if (before.vorname != after.vorname) add(AttributeType.GIVEN_NAMES)
        if (before.geburtsdatum != after.geburtsdatum) add(AttributeType.BIRTH_DATE)
        if (before.strassenzeile != after.strassenzeile) add(AttributeType.STREET_ADDRESS)
        if (before.plz != after.plz) add(AttributeType.POSTAL_CODE)
        if (before.ort != after.ort) add(AttributeType.LOCALITY)
    }

    private fun Person.applyFrom(input: PersonData) {
        name = input.name
        vorname = input.vorname
        geburtsdatum = input.geburtsdatum
        strasse = input.strasse
        hausnummer = input.hausnummer
        plz = input.plz
        ort = input.ort
    }

    private fun Person.toPersonData() = PersonData(
        id, kvnr, name, vorname, geburtsdatum, strasse, hausnummer, plz, ort, versnr
    )
}
