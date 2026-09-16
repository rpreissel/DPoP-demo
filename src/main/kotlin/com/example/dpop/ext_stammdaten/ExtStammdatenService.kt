package com.example.dpop.ext_stammdaten

import com.example.dpop.ext_stammdaten.internal.PersonRepository
import com.example.dpop.tool_api.ClaimedIdentity
import com.example.dpop.tool_api.PersonDirectory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

@Service
class ExtStammdatenService(private val personRepository: PersonRepository) : PersonDirectory {

    fun fetchStammdaten(): String {
        val persons = personRepository.findAll()
        if (persons.isEmpty()) {
            return "ext_stammdaten: no persons found"
        }
        return persons.joinToString(separator = ", ", prefix = "ext_stammdaten: ") { p ->
            "${p.vorname} ${p.name}"
        }
    }

    override fun findPersonIdByKvnr(kvnr: String): Long? =
        personRepository.findByKvnr(kvnr)?.id

    override fun matchesStammdaten(personId: Long, claimed: ClaimedIdentity): Boolean {
        val person = personRepository.findByIdOrNull(personId) ?: return false
        // null = the attestation didn't include the attribute; it is not compared. Same exact
        // comparison as before for the attributes that ARE present.
        return (claimed.name == null || person.name == claimed.name) &&
            (claimed.vorname == null || person.vorname == claimed.vorname) &&
            (claimed.geburtsdatum == null || person.geburtsdatum == claimed.geburtsdatum) &&
            (claimed.strasse == null || person.strasse == claimed.strasse) &&
            (claimed.hausnummer == null || person.hausnummer == claimed.hausnummer) &&
            (claimed.plz == null || person.plz == claimed.plz) &&
            (claimed.ort == null || person.ort == claimed.ort)
    }

    override fun matchesName(personId: Long, name: String, vorname: String): Boolean {
        val person = personRepository.findByIdOrNull(personId) ?: return false
        return person.name.equals(name.trim(), ignoreCase = true) &&
            person.vorname.equals(vorname.trim(), ignoreCase = true)
    }

    override fun displayName(personId: Long): String? {
        val person = personRepository.findByIdOrNull(personId) ?: return null
        return listOfNotNull(person.vorname, person.name).joinToString(" ").ifBlank { null }
    }

    fun findPersonByKvnr(kvnr: String): PersonData? =
        personRepository.findByKvnr(kvnr)
            ?.let { PersonData(it.id, it.kvnr, it.name, it.vorname, it.geburtsdatum) }

    fun findPersonById(personId: Long): PersonData? =
        personRepository.findByIdOrNull(personId)
            ?.let { PersonData(it.id, it.kvnr, it.name, it.vorname, it.geburtsdatum) }
}
