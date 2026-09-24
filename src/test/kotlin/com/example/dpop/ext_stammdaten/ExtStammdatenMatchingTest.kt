package com.example.dpop.ext_stammdaten

import com.example.dpop.ext_stammdaten.internal.MrzName
import com.example.dpop.ext_stammdaten.internal.Person
import com.example.dpop.ext_stammdaten.internal.PersonRepository
import com.example.dpop.tool_api.ClaimedIdentity
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import java.util.Optional

/**
 * How the register compares what a document attests with what it has on file: names in their
 * passport (MRZ) form, the street as one line against its own two fields.
 */
class ExtStammdatenMatchingTest : BehaviorSpec({

    val geburtsdatum = LocalDate.of(1980, 2, 29)

    fun registerWith(person: Person): ExtStammdatenService {
        val repository = mockk<PersonRepository>()
        every { repository.findById(1L) } returns Optional.of(person)
        return ExtStammdatenService(repository)
    }

    given("MRZ form") {
        then("it spells out umlauts, drops other diacritics and joins parts with <") {
            MrzName.of("Müller-Lüdenscheidt") shouldBe "MUELLER<LUEDENSCHEIDT"
            MrzName.of(" Straße ") shouldBe "STRASSE"
            MrzName.of("José María") shouldBe "JOSE<MARIA"
            MrzName.of("O'Brien") shouldBe "O<BRIEN"
        }
    }

    given("Jürgen Müller-Lüdenscheidt on file") {
        val register = registerWith(
            Person(kvnr = "A123456789", name = "Müller-Lüdenscheidt", vorname = "Jürgen", strasse = "Heidestraße", hausnummer = "17", geburtsdatum = geburtsdatum)
        )

        then("a passport chip's reading matches") {
            register.matchesStammdaten(1L, ClaimedIdentity(name = "MUELLER LUEDENSCHEIDT", vorname = "JUERGEN", geburtsdatum = geburtsdatum)) shouldBe true
        }

        then("an eID card's upper-case reading matches, street line included") {
            register.matchesStammdaten(
                1L,
                ClaimedIdentity(name = "MÜLLER-LÜDENSCHEIDT", vorname = "JÜRGEN", geburtsdatum = geburtsdatum, strasse = "HEIDESTRASSE 17")
            ) shouldBe true
        }

        then("a different person does not") {
            register.matchesStammdaten(1L, ClaimedIdentity(name = "MUELLER", vorname = "JUERGEN", geburtsdatum = geburtsdatum)) shouldBe false
            register.matchesStammdaten(1L, ClaimedIdentity(name = "Müller-Lüdenscheidt", vorname = "Jürgen", strasse = "Heidestraße 18")) shouldBe false
        }

        then("typed Personalien compare the same way") {
            register.matchesPersonalien(1L, "mueller-luedenscheidt", "juergen", geburtsdatum) shouldBe true
        }
    }

    given("a name longer than a passport's name field") {
        val register = registerWith(
            Person(kvnr = "A123456789", name = "Schmidt-Wolkenstein", vorname = "Maximilian Friedrich Alexander", geburtsdatum = geburtsdatum)
        )

        then("the chip's truncated reading still matches - the register asks no more than the chip holds") {
            // SCHMIDT<WOLKENSTEIN<<MAXIMILIAN<FRIEDRI = 39 characters
            register.matchesStammdaten(1L, ClaimedIdentity(name = "SCHMIDT WOLKENSTEIN", vorname = "MAXIMILIAN FRIEDRI", geburtsdatum = geburtsdatum)) shouldBe true
        }
    }
})
