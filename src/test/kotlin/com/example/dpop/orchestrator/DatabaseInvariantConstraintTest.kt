package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.springframework.dao.DataIntegrityViolationException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * The database constraints of docs/invarianten.md (V22, V23) really bite: each rule is broken
 * directly in SQL, past all code, and the database must refuse. Plus the one place where SQL
 * repeats code knowledge - the list of methods that allow several instances - checked against the
 * tool descriptors, so the two cannot drift apart.
 */
class DatabaseInvariantConstraintTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    private fun channelId(): UUID = UUID.fromString(post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String)

    private fun insertJourney(channel: UUID, lifecycle: String) = jdbcTemplate.update(
        """INSERT INTO orchestrator.auth_journey (id, channel_session_id, intent, lifecycle, state_type, state, attempt_budget, created_at, expires_at)
           VALUES (?, ?, 'LOGIN', ?, 'x', '{}', 3, CURRENT_TIMESTAMP, DATEADD('HOUR', 1, CURRENT_TIMESTAMP))""",
        UUID.randomUUID(), channel, lifecycle
    )

    init {
        given("the database constraints for the invariants") {
            then("I-3: a second STARTED journey on a channel is refused, a finished one is fine") {
                val channel = channelId() // already has its STARTED entry journey
                insertJourney(channel, "CANCELLED")
                shouldThrow<DataIntegrityViolationException> { insertJourney(channel, "STARTED") }
            }

            then("I-1: an ended channel cannot keep its evidence") {
                val channel = channelId()
                val evidence = UUID.randomUUID()
                jdbcTemplate.update(
                    "INSERT INTO orchestrator.auth_evidence (id, account_id, amr_evidence, updated_at) VALUES (?, 1, '[]', CURRENT_TIMESTAMP)", evidence
                )
                shouldThrow<DataIntegrityViolationException> {
                    jdbcTemplate.update(
                        "UPDATE orchestrator.channel_session SET state = 'LOGGED_OUT', auth_evidence_id = ? WHERE id = ?", evidence, channel
                    )
                }
            }

            then("I-4: an authenticated channel without evidence is refused") {
                val channel = channelId()
                shouldThrow<DataIntegrityViolationException> {
                    jdbcTemplate.update("UPDATE orchestrator.channel_session SET state = 'AUTHENTICATED', auth_evidence_id = NULL WHERE id = ?", channel)
                }
            }

            then("I-13: a second active password for an account is refused, a second device is fine") {
                val accountId = accountFixtures.seedAccount(methods = emptyList())
                fun insertMethod(method: String) = jdbcTemplate.update(
                    """INSERT INTO account.auth_method (id, account_id, method, enrollment_type, enrollment_id, active, created_at)
                       VALUES (?, ?, ?, 't', ?, TRUE, CURRENT_TIMESTAMP)""",
                    UUID.randomUUID(), accountId, method, UUID.randomUUID().toString()
                )
                insertMethod("device")
                insertMethod("device")
                insertMethod("password")
                shouldThrow<DataIntegrityViolationException> { insertMethod("password") }
            }

            then("the SQL list of multi-instance methods matches the tool descriptors") {
                val fromDescriptors = toolRegistry.descriptors().filter { it.allowsMultipleInstances }.map { it.method }.toSet()
                val migration = Files.readString(
                    generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                        .map { it.resolve("src/main/resources/db/migration/account/V23__eine_aktive_singleton_methode.sql") }
                        .first { Files.exists(it) }
                )
                val fromSql = Regex("""method NOT IN \(([^)]*)\)""").findAll(migration)
                    .map { match -> match.groupValues[1].split(",").map { it.trim().trim('\'') }.toSet() }
                    .toSet()
                fromSql shouldBe setOf(fromDescriptors)
            }
        }
    }
}
