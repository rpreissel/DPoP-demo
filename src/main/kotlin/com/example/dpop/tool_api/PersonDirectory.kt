package com.example.dpop.tool_api

import java.time.LocalDate

/**
 * Resolves an identification tool's own identifier to the external person id that
 * `ToolOutcome.Completed.Identified` expects - never the person's master data itself.
 */
interface PersonDirectory {
    /**
     * @return the person id (Partnernummer) for this KVNR, or `null` if no matching person is found.
     */
    fun findPersonIdByKvnr(kvnr: String): String?

    /**
     * @return the person id for this Partnernummer - its canonical form, since the Partnernummer is
     *   the person id (ADR-34) - or `null` if it is malformed or no such person exists. What a
     *   Partner, who has no KVNR, identifies by; an insured person is asked for the KVNR first.
     */
    fun findPersonIdByPartnernr(partnernr: String): String?

    /**
     * Whether the master data on file for [personId] match every attribute in [claimed] - lets an
     * identification tool verify a claimed identity (e.g. what an eID card attests) without ever handing
     * the master data itself back across the port, same rule as [findPersonIdByKvnr]. Names
     * compare in their passport (MRZ) form - case, umlaut spelling and diacritics do not count,
     * because each document writes them its own way (`MUELLER` on a chip, `MÜLLER` on an eID card).
     */
    fun matchesMasterData(personId: String, claimed: ClaimedIdentity): Boolean

    /**
     * Whether name and birthdate on file for [personId] match - the narrow sibling of
     * [matchesMasterData], for a procedure that only ever learns what a person types in
     * (`ident-fsc`), not a full set of card data. Names compare the same way as there. Same
     * rule: the answer crosses the port, the master data never does.
     */
    fun matchesPersonalDetails(personId: String, familyName: String, givenNames: String, birthDate: LocalDate): Boolean

    /**
     * "Vorname Name" for [personId], or `null` if unknown - a deliberate, narrow exception to the
     * "answer crosses the port, master data never does" rule above: this exists only so the demo
     * UI can show who is logged in (`TokenService.idClaims`'s `name` claim, docs/05-api.md
     * ID-Token-Claims - not part of the production contract), never for a policy/journey decision.
     * Unlike [matchesMasterData]/[matchesPersonalDetails], which exist precisely to avoid handing this out,
     * this one hands out only the name - never address/birthdate/KVNR.
     */
    fun displayName(personId: String): String?

    /**
     * The Versicherungsnummer of [personId], or `null` (not insured with us, or unknown). Like
     * [findPersonIdByKvnr] an identifier, not master data - it becomes the account's `INSURANCE_NUMBER` anchor
     * when the person is bound (ADR-34), which is why it may cross the port.
     */
    fun insuranceNumberOf(personId: String): String?
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
    val familyName: String? = null,
    val givenNames: String? = null,
    val birthDate: LocalDate? = null,
    /** Street and house number in one line, as a document attests it (`AttributeType.STREET_ADDRESS`). */
    val streetAddress: String? = null,
    val postalCode: String? = null,
    val locality: String? = null
)
