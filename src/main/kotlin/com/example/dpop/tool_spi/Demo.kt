package com.example.dpop.tool_spi

/**
 * Reserved key in [ToolOutcome.InProgress.data] for a nested bag of demo-only values (e.g. a
 * just-issued TAN). Never part of the production `stepData` contract - the caller lifts this key
 * out into a separate `demo` block before building the client response.
 */
const val DEMO_DATA_KEY = "demo"

/**
 * Builds the `data` entry a tool uses to attach demo-only values, e.g.:
 * ```
 * data = mapOf("missingFields" to listOf("tan"), demoData("tan" to issued.plainTan))
 * ```
 */
fun demoData(vararg values: Pair<String, Any?>): Pair<String, Map<String, Any?>> = DEMO_DATA_KEY to mapOf(*values)

/**
 * The email address this demo environment treats as already confirmed. Shared as one literal
 * across every method module so a lookup-based login tool can resolve the same account another
 * tool's demo flow just enrolled.
 */
const val DEMO_EMAIL = "max.mustermann@example.com"

/**
 * One of `V2__testdata.sql`'s three seeded persons, with every attribute any demo tool prefills
 * anywhere (auth_email/password/sms's email, id_fsc/id_eid's KVNR/name/address/FSC code). One
 * shared shape so a single picker (wired centrally in `ToolControllerSupport.demoInfo`, not per
 * tool) covers every method and ident procedure at once.
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
    val fscCode: String
)

/**
 * Same three persons/kvnrs as `V2__testdata.sql` and `KcDemoAccountSeeder.TEST_PERSONS` (auth_email) -
 * duplicated here (not imported) for the same module-boundary reason as [DEMO_EMAIL]/`DEMO_PASSWORD`.
 * The first entry matches [DEMO_EMAIL] so existing single-value prefill behavior is unchanged.
 */
val DEMO_PERSONS = listOf(
    DemoPerson(
        kvnr = "A123456789", name = "Muster", vorname = "Max", email = DEMO_EMAIL,
        strasse = "Musterstraße", hausnummer = "1", plz = "12345", ort = "Musterstadt",
        geburtsdatum = "1985-06-15", fscCode = "VALIDCODE"
    ),
    DemoPerson(
        kvnr = "B987654321", name = "Beispiel", vorname = "Erika", email = "erika.beispiel@example.com",
        strasse = "Beispielweg", hausnummer = "42", plz = "54321", ort = "Beispielhausen",
        geburtsdatum = "1990-11-02", fscCode = "ERIKA123"
    ),
    DemoPerson(
        kvnr = "C111111111", name = "Doe", vorname = "Jane", email = "jane.doe@example.com",
        strasse = "Hauptstraße", hausnummer = "7a", plz = "10115", ort = "Berlin",
        geburtsdatum = "1978-03-30", fscCode = "JANE2026"
    )
)
