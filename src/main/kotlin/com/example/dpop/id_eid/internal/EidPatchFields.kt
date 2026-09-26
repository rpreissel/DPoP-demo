package com.example.dpop.id_eid.internal

import java.time.LocalDate

/**
 * One PATCH call's worth of ident-eid input, bundled instead of passed as separate parameters -
 * only the fields for the current step are usually non-null (docs/06-ablaeufe.md, "only the
 * fields being supplied or corrected need to be sent").
 */
data class EidPatchFields(
    val familyName: String? = null,
    val givenNames: String? = null,
    val birthDate: LocalDate? = null,
    /** Street and house number in one line, as the card's `Street` carries them. */
    val streetAddress: String? = null,
    val postalCode: String? = null,
    val locality: String? = null,
    val restrictedId: String? = null,
    val pin: String? = null
)
