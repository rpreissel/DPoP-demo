package com.example.dpop.ext_stammdaten

import com.example.dpop.ext_stammdaten.internal.MrzName
import com.example.dpop.ext_stammdaten.internal.PersonRepository
import com.example.dpop.ext_stammdaten.internal.Person
import com.example.dpop.tool_api.ClaimedIdentity
import com.example.dpop.tool_api.Kvnr
import com.example.dpop.tool_api.PersonDirectory
import com.example.dpop.tool_api.normalizeKvnr
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/** Raised when the register refuses a change; its refusals are not this application's error contract. */
class PersonRejectedException(message: String) : RuntimeException(message)

@Service
class ExtStammdatenService(private val personRepository: PersonRepository) : PersonDirectory {

    override fun findPersonIdByKvnr(kvnr: String): Long? =
        personRepository.findByKvnr(normalizeKvnr(kvnr))?.id

    override fun matchesStammdaten(personId: Long, claimed: ClaimedIdentity): Boolean {
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

    override fun matchesPersonalien(personId: Long, name: String, vorname: String, geburtsdatum: LocalDate): Boolean {
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

    override fun displayName(personId: Long): String? {
        val person = personRepository.findByIdOrNull(personId) ?: return null
        return listOfNotNull(person.vorname, person.name).joinToString(" ").ifBlank { null }
    }

    fun findPersonByKvnr(kvnr: String): PersonData? =
        personRepository.findByKvnr(normalizeKvnr(kvnr))?.toPersonData()

    fun findPersonById(personId: Long): PersonData? =
        personRepository.findByIdOrNull(personId)?.toPersonData()

    // ------------------------------------------------------------------ register management (/ext/)

    @Transactional(readOnly = true)
    fun allePersonen(): List<PersonData> = personRepository.findAll().sortedBy { it.id }.map { it.toPersonData() }

    /** @throws PersonRejectedException for a malformed or already registered KVNR. */
    @Transactional
    fun anlegen(input: PersonData): PersonData {
        val kvnr = validKvnr(input.kvnr)
        if (personRepository.findByKvnr(kvnr) != null) throw PersonRejectedException("KVNR $kvnr ist bereits registriert")
        return personRepository.save(Person(kvnr = kvnr).apply { applyFrom(input) }).toPersonData()
    }

    /** @return null when no such person exists. The KVNR is the register's key and stays as it is. */
    @Transactional
    fun aendern(personId: Long, input: PersonData): PersonData? {
        val person = personRepository.findByIdOrNull(personId) ?: return null
        person.applyFrom(input)
        return person.toPersonData()
    }

    private fun validKvnr(raw: String?): String =
        Kvnr.ofOrNull(raw.orEmpty())?.value ?: throw PersonRejectedException("KVNR muss ein Buchstabe und neun Ziffern sein")

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
        id, kvnr, name, vorname, geburtsdatum, strasse, hausnummer, plz, ort
    )
}
