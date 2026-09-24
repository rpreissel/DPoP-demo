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
     * Whether name and birthdate on file for [personId] match - the narrow sibling of
     * [matchesStammdaten], for a procedure that only ever learns what a person types in
     * (`ident-fsc`), not a full set of Ausweisdaten. Unlike [matchesStammdaten] the names compare
     * forgivingly (trimmed, case-insensitive): they are typed, not read off a chip. Same rule: the
     * answer crosses the port, the master data never does.
     */
    fun matchesPersonalien(personId: Long, name: String, vorname: String, geburtsdatum: LocalDate): Boolean

    /**
     * "Vorname Name" for [personId], or `null` if unknown - a deliberate, narrow exception to the
     * "answer crosses the port, master data never does" rule above: this exists only so the demo
     * UI can show who is logged in (`TokenService.idClaims`'s `name` claim, docs/05-api.md
     * ID-Token-Claims - not part of the production contract), never for a policy/journey decision.
     * Unlike [matchesStammdaten]/[matchesPersonalien], which exist precisely to avoid handing this out,
     * this one hands out only the name - never address/birthdate/KVNR.
     */
    fun displayName(personId: Long): String?
}

/**
 * The canonical form a KVNR is compared and looked up in: trimmed and uppercased. Every caller of
 * [PersonDirectory.findPersonIdByKvnr] goes through this, so a user typing `" a123456789 "` finds
 * the same person as one typing `"A123456789"`. Shape validation is [Kvnr]'s job, not this one's.
 */
fun normalizeKvnr(kvnr: String): String = kvnr.trim().uppercase()

/**
 * The attributes a claimed identity (e.g. an eID card read) can be verified against - never the
 * KVNR, which resolves [PersonDirectory.findPersonIdByKvnr] itself. `null` means the attestation
 * did not include that attribute; it is simply not compared, so partial attestations (selective
 * disclosure) verify against the subset they actually carry.
 */
data class ClaimedIdentity(
    val name: String? = null,
    val vorname: String? = null,
    val geburtsdatum: LocalDate? = null,
    val strasse: String? = null,
    val hausnummer: String? = null,
    val plz: String? = null,
    val ort: String? = null
)
