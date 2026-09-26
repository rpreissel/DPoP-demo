package com.example.dpop.tool_api

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

/**
 * Every tool-session table must actually be swept.
 *
 * Retention used to be ten near-identical `@Scheduled` jobs, one per module, each with its own
 * copy of the 24h period. A module that never got a job was not a broken implementation of
 * anything - it was simply a file nobody wrote, and `id_kvnr.ident_tool_session` had been
 * accumulating submitted KVNRs indefinitely because of exactly that.
 *
 * The table list is read from the live schema rather than hard-coded, so a NEW module's table is
 * covered by this test the moment its migration lands - which is the only way this stays a real
 * guarantee instead of a second list to forget.
 */
@SpringBootTest
@ActiveProfiles("test")
class ToolSessionCoverageTest : BehaviorSpec() {

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var sweepers: List<ToolSessionSweeper>

    init {
        given("every *_tool_session table in the schema") {
            then("each one is emptied of expired rows by some module's sweeper") {
                val tables = toolSessionTables()
                tables.shouldNotBeEmptyList()

                // Seed one long-expired row per table, then run the sweep exactly as the driver
                // does. A table nobody sweeps still has its row afterwards - which names the
                // module that is missing a ToolSessionSweeper.
                val expired = Instant.now().minusSeconds(365 * 24 * 3600)
                tables.forEach { table -> insertExpiredRow(table, expired) }

                sweepers.forEach { it.sweep(Instant.now().minusSeconds(24 * 3600)) }

                val stillPopulated = tables.filter { table ->
                    val remaining = jdbcTemplate.queryForObject(
                        "select count(*) from $table where created_at < ?", Long::class.java, java.sql.Timestamp.from(expired.plusSeconds(1))
                    ) ?: 0L
                    remaining > 0
                }
                stillPopulated.shouldBeEmpty()
            }
        }
    }

    /**
     * `orchestrator.tool_session` is excluded: it is the orchestrator's own session bookkeeping,
     * swept by `orchestrator.session.RetentionJob` together with channels and journeys, not by a
     * method module's sweeper.
     */
    private fun toolSessionTables(): List<String> =
        jdbcTemplate.queryForList(
            """
            select table_schema || '.' || table_name as t
            from information_schema.tables
            where table_name like '%TOOL_SESSION' and table_schema <> 'ORCHESTRATOR'
            """.trimIndent(),
            String::class.java
        ).requireNoNulls().sorted()

    /**
     * Only `created_at` is set; every other column is left to its own default or NULL. If a table
     * ever gains a NOT NULL column without a default this insert fails loudly, which is the right
     * outcome - it means this test no longer knows the shape it is asserting about.
     */
    private fun insertExpiredRow(table: String, createdAt: Instant) {
        val idColumn = jdbcTemplate.queryForList(
            """
            select column_name from information_schema.columns
            where table_schema || '.' || table_name = ? and is_nullable = 'NO' and column_default is null
              and column_name <> 'CREATED_AT'
            """.trimIndent(),
            String::class.java, table
        ).requireNoNulls()
        val columns = (listOf("created_at") + idColumn).joinToString(", ")
        val values = (listOf(java.sql.Timestamp.from(createdAt)) + idColumn.map { placeholderFor(table, it) }).toTypedArray()
        jdbcTemplate.update(
            "insert into $table ($columns) values (${values.joinToString(", ") { "?" }})",
            *values
        )
    }

    /**
     * A syntactically valid value of the right type - the row's content is irrelevant here, only
     * that it exists and is old. Text values respect the declared length: several of these columns
     * are narrow (a PIN, an activation code), and a full UUID would not fit.
     */
    private fun placeholderFor(table: String, column: String): Any {
        val meta = jdbcTemplate.queryForMap(
            """
            select data_type, character_maximum_length
            from information_schema.columns
            where table_schema || '.' || table_name = ? and column_name = ?
            """.trimIndent(),
            table, column
        )
        val type = meta["DATA_TYPE"]?.toString().orEmpty()
        val maxLength = (meta["CHARACTER_MAXIMUM_LENGTH"] as? Number)?.toInt() ?: Int.MAX_VALUE
        return when {
            type.contains("UUID") -> java.util.UUID.randomUUID()
            type.contains("CHAR") -> java.util.UUID.randomUUID().toString().replace("-", "").take(minOf(maxLength, 32))
            type.contains("BOOLEAN") -> false
            type.contains("TIMESTAMP") || type.contains("DATE") -> java.sql.Timestamp.from(Instant.now())
            type.contains("INT") || type.contains("NUMERIC") || type.contains("DECIMAL") -> 1L
            else -> java.util.UUID.randomUUID().toString().take(minOf(maxLength, 32))
        }
    }

    private fun List<String>.shouldNotBeEmptyList() {
        check(isNotEmpty()) { "Keine *_tool_session-Tabellen gefunden - das Schema wurde nicht migriert?" }
    }
}
