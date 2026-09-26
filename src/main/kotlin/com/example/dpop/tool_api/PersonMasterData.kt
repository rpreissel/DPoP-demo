package com.example.dpop.tool_api

import java.time.LocalDate

/**
 * The master data of a person, for exactly one consumer: mirroring a bound account into its
 * identity provider (Keycloak), which puts name, address and identifiers into the tokens.
 *
 * A port of its own next to [PersonDirectory], deliberately: that one exists so identification
 * tools can VERIFY a person without the master data ever crossing - its rule stays intact. Here the
 * data does cross, because the mirror's whole job is to carry it; naming that as a separate port
 * keeps it visible who reads master data and why (review 2026-09, ADR-35: the core reaches a
 * simulated foreign system only through a port).
 */
interface PersonMasterData {
    /** The record of [personId], or `null` if the directory does not know it. */
    fun masterDataOf(personId: String): PersonRecord?
}

/** What the directory holds about a person, as the account mirror needs it. */
data class PersonRecord(
    val kvnr: String?,
    val name: String?,
    val vorname: String?,
    val geburtsdatum: LocalDate?,
    /** Street and house number as one line - the form documents attest it (`AttributeType.STREET_ADDRESS`). */
    val strasse: String?,
    val plz: String?,
    val ort: String?,
    /** Versicherungsnummer, only for a person insured with us. */
    val versnr: String?
)
