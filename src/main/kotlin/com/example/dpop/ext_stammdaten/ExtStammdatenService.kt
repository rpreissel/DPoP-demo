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
        return person.name == claimed.name &&
            person.vorname == claimed.vorname &&
            person.geburtsdatum == claimed.geburtsdatum &&
            person.strasse == claimed.strasse &&
            person.hausnummer == claimed.hausnummer &&
            person.plz == claimed.plz &&
            person.ort == claimed.ort
    }

    fun findPersonByKvnr(kvnr: String): PersonData? =
        personRepository.findByKvnr(kvnr)
            ?.let { PersonData(it.id, it.kvnr, it.name, it.vorname) }

    fun findPersonById(personId: Long): PersonData? =
        personRepository.findByIdOrNull(personId)
            ?.let { PersonData(it.id, it.kvnr, it.name, it.vorname) }
}
