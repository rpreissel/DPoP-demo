package com.example.dpop.account.internal

import com.example.dpop.orchestrator.IntegrationTestSupport
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Pins the backfill INSERT in V38__backfill_person_id_anchor.sql (docs/ideen/account-attribute-
 * und-trust-vereinheitlichen.md, Paket 3): V38 itself already ran once against the empty test DB
 * at context startup (nothing to backfill there), so this re-executes its actual INSERT
 * statement - read straight from the migration file, not retyped - against manually seeded
 * "pre-migration" account rows to prove the upgrade logic itself, not just that it doesn't
 * crash on an empty database.
 */
class PersonIdAnchorBackfillMigrationTest : IntegrationTestSupport() {
    init {
        given("an existing account with a person_id but no account_anchor row for it") {
            `when`("the V38 backfill INSERT runs") {
                then("it materializes a normalized person_id anchor pointing at that account") {
                    val createdAt = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.MILLIS)
                    jdbcTemplate.update(
                        "INSERT INTO account (id, person_id, created_at, identifications, authentication_methods, version) " +
                            "VALUES (?, ?, ?, '[]', '[]', 0)",
                        9001L, 42L, java.sql.Timestamp.from(createdAt)
                    )

                    jdbcTemplate.update(backfillInsertStatement())

                    val rows = jdbcTemplate.queryForList(
                        "SELECT anchor_value, account_id, established_at FROM account_anchor WHERE anchor_type = 'person_id' AND account_id = 9001"
                    )
                    rows shouldHaveSize 1
                    rows.single()["ANCHOR_VALUE"] shouldBe "42"
                }
                then("running it again is a no-op (idempotent)") {
                    jdbcTemplate.update(
                        "INSERT INTO account (id, person_id, created_at, identifications, authentication_methods, version) " +
                            "VALUES (?, ?, ?, '[]', '[]', 0)",
                        9002L, 43L, java.sql.Timestamp.from(Instant.now())
                    )
                    val insert = backfillInsertStatement()
                    jdbcTemplate.update(insert)
                    jdbcTemplate.update(insert)

                    val rows = jdbcTemplate.queryForList(
                        "SELECT anchor_value FROM account_anchor WHERE anchor_type = 'person_id' AND account_id = 9002"
                    )
                    rows shouldHaveSize 1
                }
            }
        }

        given("an account with person_id = NULL") {
            `when`("the V38 backfill INSERT runs") {
                then("it stays anchor-less") {
                    jdbcTemplate.update(
                        "INSERT INTO account (id, person_id, created_at, identifications, authentication_methods, version) " +
                            "VALUES (?, NULL, ?, '[]', '[]', 0)",
                        9003L, java.sql.Timestamp.from(Instant.now())
                    )

                    jdbcTemplate.update(backfillInsertStatement())

                    val rows = jdbcTemplate.queryForList(
                        "SELECT anchor_value FROM account_anchor WHERE anchor_type = 'person_id' AND account_id = 9003"
                    )
                    rows shouldHaveSize 0
                }
            }
        }
    }

    /** Extracts just the backfill INSERT statement from the migration file - the CREATE UNIQUE INDEX already ran at boot. */
    private fun backfillInsertStatement(): String {
        val sql = File("src/main/resources/db/migration/V38__backfill_person_id_anchor.sql").readText()
        val start = sql.indexOf("INSERT INTO account_anchor")
        val end = sql.indexOf(";", start)
        return sql.substring(start, end)
    }
}
