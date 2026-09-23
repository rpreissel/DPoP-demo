package com.example.dpop.tool_spi

/**
 * The email address this demo environment treats as already confirmed. Shared as one literal
 * across every method module so a lookup-based login tool can resolve the same account another
 * tool's demo flow just enrolled.
 */
const val DEMO_EMAIL = "max.mustermann@example.com"

/**
 * One of `demo_seed/V16__testdata.sql`'s three seeded persons, with every attribute any demo tool prefills
 * anywhere (auth_email/password/sms's email, id_fsc/id_eid's KVNR/name/address/FSC code, and the
 * eid card's fixed restricted identifier). One shared shape so a single picker (wired centrally
 * in `ToolControllerSupport.demoInfo`, not per tool) covers every method and ident procedure at
 * once.
 */
data class DemoPerson(
    val kvnr: String,
    val name: String,
    val vorname: String,
    val email: String,
    val strasse: String,
    val hausnummer: String,
    val plz: String,
    val ort: String,
    val geburtsdatum: String,
    val fscCode: String,
    val restrictedId: String
)

/**
 * Same three persons/kvnrs as `demo_seed/V16__testdata.sql` and `KcDemoAccountSeeder.TEST_PERSONS` (demo_seed) -
 * duplicated here (not imported) for the same module-boundary reason as [DEMO_EMAIL]/`DEMO_PASSWORD`.
 * The first entry matches [DEMO_EMAIL] so existing single-value prefill behavior is unchanged.
 */
val DEMO_PERSONS = listOf(
    DemoPerson(
        kvnr = "A123456789", name = "Muster", vorname = "Max", email = DEMO_EMAIL,
        strasse = "Musterstraße", hausnummer = "1", plz = "12345", ort = "Musterstadt",
        geburtsdatum = "1985-06-15", fscCode = "VALIDCODE", restrictedId = "T0103005K1D5S0V8T9W6UM2RTX"
    ),
    DemoPerson(
        kvnr = "B987654321", name = "Beispiel", vorname = "Erika", email = "erika.beispiel@example.com",
        strasse = "Beispielweg", hausnummer = "42", plz = "54321", ort = "Beispielhausen",
        geburtsdatum = "1990-11-02", fscCode = "ERIKA123", restrictedId = "T0208011X7Y2Q4M6B3LT0T28WJ"
    ),
    DemoPerson(
        kvnr = "C111111111", name = "Doe", vorname = "Jane", email = "jane.doe@example.com",
        strasse = "Hauptstraße", hausnummer = "7a", plz = "10115", ort = "Berlin",
        geburtsdatum = "1978-03-30", fscCode = "JANE2026", restrictedId = "T0304223A9B1N7K5D2PN1S44QE"
    )
)
