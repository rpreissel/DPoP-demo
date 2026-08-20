package com.example.dpop.ext_stammdaten

import com.example.dpop.ext_stammdaten.internal.PersonRepository
import org.springframework.stereotype.Service
import java.util.Optional

@Service
class ExtStammdatenService(private val personRepository: PersonRepository) {

    fun fetchStammdaten(): String {
        val persons = personRepository.findAll()
        if (persons.isEmpty()) {
            return "ext_stammdaten: no persons found"
        }
        return persons.joinToString(separator = ", ", prefix = "ext_stammdaten: ") { p ->
            "${p.vorname} ${p.name}"
        }
    }

    fun findPersonIdByKvnr(kvnr: String): Optional<Long> =
        personRepository.findByKvnr(kvnr).map { it.id }

    fun findPersonByKvnr(kvnr: String): Optional<PersonData> =
        personRepository.findByKvnr(kvnr)
            .map { PersonData(it.id, it.kvnr, it.name, it.vorname) }

    fun findPersonById(personId: Long): Optional<PersonData> =
        personRepository.findById(personId)
            .map { PersonData(it.id, it.kvnr, it.name, it.vorname) }
}
