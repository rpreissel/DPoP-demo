package com.example.dpop.ext_personenverzeichnis

import java.time.LocalDate

/**
 * The register person's outward projection. Address fields (strasse/hausnummer/plz/ort) are
 * nullable because the register is the authority but not every consumer needs them - the Keycloak
 * mirror uses them to prefer the register value over an attested claim, with the claim as
 * gap-filling fallback for Interessenten.
 */
data class PersonData(
    /** The Partnernummer - the person id (ADR-34). */
    val id: String?,
    val kvnr: String?,
    val name: String?,
    val vorname: String?,
    val geburtsdatum: LocalDate?,
    val strasse: String? = null,
    val hausnummer: String? = null,
    val plz: String? = null,
    val ort: String? = null,
    /** Versicherungsnummer, only for a person insured with us (eight digits). */
    val versnr: String? = null
)

/**
 * Street and house number as one line - the form documents attest an address in
 * (`AttributeType.STRASSE`). The register keeps them apart (docs/08-projektrahmen.md P-4); this is
 * where the two meet. An extension, not a member, so it stays out of the register's own API.
 */
val PersonData.strassenzeile: String?
    get() = listOfNotNull(strasse?.trim()?.ifBlank { null }, hausnummer?.trim()?.ifBlank { null }).joinToString(" ").ifBlank { null }
