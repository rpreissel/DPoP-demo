package com.example.dpop.account.domain

import com.example.dpop.account.application.IdentityMatchingService
import com.example.dpop.account.application.PersonLookupKey
import java.text.Normalizer

/**
 * Uppercase, German umlauts spelled out, other diacritics dropped - how a passport chip writes a
 * name. The one spelling rule for comparing names: the attestation check
 * ([IdentityMatchingService.attestationFits]) and the change log's search key ([PersonLookupKey])
 * must agree, or a person found by one is missed by the other.
 */
internal fun passportForm(value: String): String =
    Normalizer.normalize(
        value.trim().uppercase()
            .replace("Ä", "AE").replace("Ö", "OE").replace("Ü", "UE").replace("ß", "SS").replace("ẞ", "SS"),
        Normalizer.Form.NFD
    ).replace("\\p{M}".toRegex(), "").replace("[^A-Z0-9-]".toRegex(), "")
