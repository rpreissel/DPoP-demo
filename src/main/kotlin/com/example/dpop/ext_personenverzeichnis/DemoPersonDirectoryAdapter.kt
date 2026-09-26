package com.example.dpop.ext_personenverzeichnis

import com.example.dpop.tool_api.DemoPersonDirectory
import com.example.dpop.tool_api.PersonRecord
import org.springframework.stereotype.Component

/** The register's side of [DemoPersonDirectory] - demo disclosure only, see there. */
@Component
class DemoPersonDirectoryAdapter(
    private val register: Personenverzeichnis,
    private val freischaltcodes: Freischaltcodes,
) : DemoPersonDirectory {

    override fun allPersons(): List<PersonRecord> =
        with(register) { allePersonen().mapNotNull { it.toPersonRecord() } }

    override fun latestValidActivationCode(personId: String): String? =
        freischaltcodes.juengsterGueltigerCode(personId)
}
