package com.example.dpop.tool_api

import java.time.LocalDate

/**
 * Resolves an identification tool's own identifier to the external person id that
 * `ToolOutcome.Completed.Identified` expects - never the person's master data itself.
 */
interface PersonDirectory {
    /**
     * @return the person id for this KVNR, or `null` if no matching person is found.
     */
    fun findPersonIdByKvnr(kvnr: String): Long?

    /**
     * Whether the stammdaten on file for [personId] match every attribute in [claimed] - lets an
     * identification tool verify a claimed identity (e.g. eID Ausweisdaten) without ever handing
     * the master data itself back across the port, same rule as [findPersonIdByKvnr].
     */
    fun matchesStammdaten(personId: Long, claimed: ClaimedIdentity): Boolean

    /**
     * Whether the name on file for [personId] matches - the narrow sibling of
     * [matchesStammdaten], for a procedure that only ever learns a name (`ident-fsc`), not a full
     * set of Ausweisdaten. Same rule: the answer crosses the port, the master data never does.
     */
    fun matchesName(personId: Long, name: String, vorname: String): Boolean
}

/** The attributes a claimed identity (e.g. an eID card read) can be verified against - never the KVNR, which resolves [PersonDirectory.findPersonIdByKvnr] itself. */
data class ClaimedIdentity(
    val name: String,
    val vorname: String,
    val geburtsdatum: LocalDate,
    val strasse: String,
    val hausnummer: String,
    val plz: String,
    val ort: String
)
