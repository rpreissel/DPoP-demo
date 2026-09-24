package com.example.dpop.ext_stammdaten.internal

import java.text.Normalizer

/**
 * A name in the form a passport chip carries it (ICAO 9303 Part 3, MRZ): upper case, `Ä`→`AE`,
 * `Ö`→`OE`, `Ü`→`UE`, `ß`→`SS` and the like, other diacritics dropped, every separator a single
 * `<`. The register compares names in this form, so a document's reading - `MUELLER` off a chip,
 * `MÜLLER` off an eID card, `Müller` typed in - meets `Müller` on file.
 */
internal object MrzName {

    /** The MRZ name field of a TD3 passport: surname, `<<`, given names - cut off after 39 characters. */
    private const val TD3_NAME_LENGTH = 39

    private val TRANSLITERATIONS = mapOf(
        'Ä' to "AE", 'Ö' to "OE", 'Ü' to "UE", 'ß' to "SS", 'ẞ' to "SS",
        'Æ' to "AE", 'Ø' to "OE", 'Å' to "AA", 'Œ' to "OE", 'Þ' to "TH", 'Ð' to "D", 'Ł' to "L"
    )

    fun of(name: String): String {
        val transliterated = name.trim().uppercase().map { TRANSLITERATIONS[it] ?: it.toString() }.joinToString("")
        val stripped = Normalizer.normalize(transliterated, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return stripped.replace(Regex("[^A-Z0-9]+"), "<").trim('<')
    }

    /**
     * Whether [surname]/[givenNames] name the same person as [surnameOnFile]/[givenNamesOnFile].
     * Compared as the whole MRZ name field, cut where a passport cuts it: a chip truncates long
     * names, so the register must not demand more than the chip can hold.
     */
    fun sameName(surname: String, givenNames: String, surnameOnFile: String, givenNamesOnFile: String): Boolean =
        field(surname, givenNames) == field(surnameOnFile, givenNamesOnFile)

    private fun field(surname: String, givenNames: String): String = "${of(surname)}<<${of(givenNames)}".take(TD3_NAME_LENGTH)
}
