package com.example.dpop.id_eid.internal

import java.time.LocalDate

/**
 * One PATCH call's worth of ident-eid input, bundled instead of passed as separate parameters -
 * only the fields for the current step are usually non-null (docs/06-ablaeufe.md, "only the
 * fields being supplied or corrected need to be sent").
 */
data class EidPatchFields(
    val kvnr: String? = null,
    val name: String? = null,
    val vorname: String? = null,
    val geburtsdatum: LocalDate? = null,
    val strasse: String? = null,
    val hausnummer: String? = null,
    val plz: String? = null,
    val ort: String? = null,
    val pin: String? = null
)
