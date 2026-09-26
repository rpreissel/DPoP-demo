package com.example.dpop.tool_api

/**
 * Demo disclosure only: what the persona picker pre-fills the forms with. A port of its own, apart
 * from [PersonDirectory] and [PersonMasterData], so that no production path can reach it by
 * accident - listing every person is fine for a handful of demo personas and fatal for a register
 * of ten million.
 */
interface DemoPersonDirectory {
    /** Every person the register knows. */
    fun allPersons(): List<PersonRecord>

    /**
     * The plaintext of the newest still-valid code in [personId]'s letters, or `null` - the one
     * place a Freischaltcode leaves the register in plain text, and only because the demo mailbox
     * shows it anyway.
     */
    fun latestValidActivationCode(personId: String): String?
}
