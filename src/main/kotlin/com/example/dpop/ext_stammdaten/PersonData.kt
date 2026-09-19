package com.example.dpop.ext_stammdaten

import java.time.LocalDate

/**
 * The register person's outward projection. Address fields (strasse/hausnummer/plz/ort) are
 * nullable because the register is the authority but not every consumer needs them - the Keycloak
 * mirror uses them to prefer the register value over an attested claim, with the claim as
 * gap-filling fallback for Interessenten.
 */
data class PersonData(
    val id: Long?,
    val kvnr: String?,
    val name: String?,
    val vorname: String?,
    val geburtsdatum: LocalDate?,
    val strasse: String? = null,
    val hausnummer: String? = null,
    val plz: String? = null,
    val ort: String? = null
)
