package com.example.dpop.orchestrator.retention

import com.example.dpop.orchestrator.IntegrationTestSupport
import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import com.ninjasquad.springmockk.MockkBean
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * Retention against the real schema (review 2026-09-26, T-2): a channel past its window goes with
 * everything it owns - its journeys and their tool sessions, its AuthContext and its evidence -
 * while a live channel and the account itself stay. [RetentionJobTest] checks the order of calls;
 * this checks the rows.
 */
class RetentionDbTest : IntegrationTestSupport() {

    @Autowired
    private lateinit var retentionJob: RetentionJob

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    private fun count(sql: String, vararg args: Any): Long = jdbcTemplate.queryForObject(sql, Long::class.java, *args)!!

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }

        given("a registered channel past its retention window and a live one") {
            then("the old channel goes with everything it owns; the live channel and the account stay") {
                val old = UUID.fromString(registerAndAuthenticate())
                val live = UUID.fromString(post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String)

                val row = jdbcTemplate.queryForMap(
                    "SELECT account_id, auth_context_id, auth_evidence_id FROM orchestrator.channel_session WHERE id = ?", old
                )
                val accountId = row["ACCOUNT_ID"] as Long
                val authEvidenceId = row["AUTH_EVIDENCE_ID"] as UUID
                authEvidenceId shouldNotBe null
                val journeys = jdbcTemplate.queryForList("SELECT id FROM orchestrator.auth_journey WHERE channel_session_id = ?", UUID::class.java, old)
                journeys.shouldNotBeEmpty()

                // Past the channel window (14 days after expiry), nothing else touched.
                jdbcTemplate.update("UPDATE orchestrator.channel_session SET expires_at = DATEADD('DAY', -15, CURRENT_TIMESTAMP) WHERE id = ?", old)

                retentionJob.cleanup()

                count("SELECT COUNT(*) FROM orchestrator.channel_session WHERE id = ?", old) shouldBe 0
                count("SELECT COUNT(*) FROM orchestrator.auth_journey WHERE channel_session_id = ?", old) shouldBe 0
                journeys.forEach { count("SELECT COUNT(*) FROM orchestrator.tool_session WHERE journey_id = ?", it!!) shouldBe 0 }
                count("SELECT COUNT(*) FROM orchestrator.auth_evidence WHERE id = ?", authEvidenceId) shouldBe 0
                (row["AUTH_CONTEXT_ID"] as UUID?)?.let { count("SELECT COUNT(*) FROM orchestrator.auth_context WHERE id = ?", it) shouldBe 0 }

                count("SELECT COUNT(*) FROM orchestrator.channel_session WHERE id = ?", live) shouldBe 1
                count("SELECT COUNT(*) FROM account.account WHERE id = ?", accountId) shouldBe 1
            }
        }
    }
}
