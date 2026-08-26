package com.example.dpop.ext_stammdaten

import com.example.dpop.ext_stammdaten.internal.PersonRepository
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

    fun findPersonByKvnr(kvnr: String): PersonData? =
        personRepository.findByKvnr(kvnr)
            ?.let { PersonData(it.id, it.kvnr, it.name, it.vorname) }

    fun findPersonById(personId: Long): PersonData? =
        personRepository.findByIdOrNull(personId)
            ?.let { PersonData(it.id, it.kvnr, it.name, it.vorname) }
}
