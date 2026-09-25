package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.collections.shouldBeEmpty
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.web.client.HttpStatusCodeException
import java.util.UUID
import kotlin.random.Random

/**
 * Model-based test (review 2026-09, fahrplan Phase B step 11; docs/invarianten.md).
 *
 * The other suites check the paths somebody thought of. This one generates them: for each seed a
 * random sequence of actions against the real HTTP API - open a channel, sign in by SMS or password,
 * a wrong TAN, replay the last tool PATCH, log out, cancel, start method management or a peer login,
 * switch to a second device - and after EVERY step checks the invariants of the register directly in
 * the database, whatever the step was. A broken invariant is shrunk to the shortest sequence that
 * still breaks it (by dropping steps one at a time) and reported with its seed.
 *
 * 4xx answers are part of the game - most random actions are not allowed in the state they hit.
 * A 5xx is a violation on its own.
 */
class ModelBasedJourneyTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    private enum class Step {
        OPEN_CHANNEL, SIGN_IN_SMS, WRONG_TAN, SIGN_IN_PASSWORD, REPLAY_LAST_PATCH,
        LOG_OUT, CANCEL_JOURNEY, START_MANAGE, START_PEER_LOGIN, READ_CHANNEL, SWITCH_DEVICE
    }

    /** What one run remembers between steps - the "model" the random steps act on. */
    private inner class Run(private val deviceA: String) {
        private val deviceB = "binding-" + UUID.randomUUID()
        private var channel: String? = null
        private var lastPatch: Pair<String, String>? = null
        private val endedChannels = mutableSetOf<String>()
        private val finishedJourneys = mutableMapOf<String, String>()

        fun perform(step: Step): String? = when (step) {
            Step.OPEN_CHANNEL -> call(HttpMethod.POST, "/orchestrator/api/v1/app/channels", withDefaultAvailableTools("{}"))
                .second?.let { channelIdOf(it) }?.also { channel = it }.let { null }
            Step.SIGN_IN_SMS -> signInSms(correct = true)
            Step.WRONG_TAN -> signInSms(correct = false)
            Step.SIGN_IN_PASSWORD -> channel?.let { ch ->
                toolSessionOf(call(HttpMethod.POST, "/orchestrator/api/v1/channels/$ch/tools/auth-password").second)?.let { ts ->
                    patchTool("/orchestrator/api/v1/tools/$ts/auth-password", """{"password":"correct-horse-battery"}""")
                }
            }
            Step.REPLAY_LAST_PATCH -> lastPatch?.let { (url, body) -> serverError(call(HttpMethod.PATCH, url, body)) }
            Step.LOG_OUT -> channel?.let { serverError(call(HttpMethod.DELETE, "/orchestrator/api/v1/channels/$it")) }
            Step.CANCEL_JOURNEY -> channel?.let { serverError(call(HttpMethod.DELETE, "/orchestrator/api/v1/channels/$it/journey")) }
            Step.START_MANAGE -> channel?.let { serverError(call(HttpMethod.POST, "/orchestrator/api/v1/channels/$it/enrollments")) }
            Step.START_PEER_LOGIN -> channel?.let { serverError(call(HttpMethod.POST, "/orchestrator/api/v1/channels/$it/peer-logins")) }
            Step.READ_CHANNEL -> channel?.let { serverError(call(HttpMethod.GET, "/orchestrator/api/v1/channels/$it")) }
            Step.SWITCH_DEVICE -> {
                currentBindingKeyRef = if (currentBindingKeyRef == deviceA) deviceB else deviceA
                channel = null
                null
            }
        }

        private fun signInSms(correct: Boolean): String? {
            val ch = channel ?: return null
            val before = smsGateway.outbox().firstOrNull()?.sequence ?: 0
            val activated = call(HttpMethod.POST, "/orchestrator/api/v1/channels/$ch/tools/auth-sms")
            serverError(activated)?.let { return it }
            val ts = toolSessionOf(activated.second) ?: return null
            val tan = smsGateway.outbox().firstOrNull()?.takeIf { it.sequence > before }?.tan ?: return null
            return patchTool("/orchestrator/api/v1/tools/$ts/auth-sms", """{"tan":"${if (correct) tan else wrongTan(tan)}"}""")
        }

        private fun patchTool(url: String, body: String): String? {
            val result = call(HttpMethod.PATCH, url, body)
            if (result.first in 200..299) lastPatch = url to body
            return serverError(result)
        }

        /** Every invariant of docs/invarianten.md this model can observe - checked after each step. */
        fun violations(): List<String> = buildList {
            // I-1: an ended channel stays ended.
            jdbcTemplate.queryForList("SELECT id, state FROM orchestrator.channel_session").forEach { row ->
                val id = row["ID"].toString()
                val state = row["STATE"].toString()
                if (id in endedChannels && state == "AUTHENTICATED") add("I-1: channel $id authenticated again after it ended")
                if (state == "LOGGED_OUT" || state == "EXPIRED") endedChannels += id
            }
            // I-2: a finished journey never changes again.
            jdbcTemplate.queryForList("SELECT id, lifecycle FROM orchestrator.auth_journey").forEach { row ->
                val id = row["ID"].toString()
                val lifecycle = row["LIFECYCLE"].toString()
                finishedJourneys[id]?.let { before -> if (before != lifecycle) add("I-2: journey $id went from $before to $lifecycle") }
                if (lifecycle in setOf("CONSUMED", "CANCELLED", "FAILED", "EXPIRED")) finishedJourneys[id] = lifecycle
            }
            // I-3: at most one running journey per channel.
            jdbcTemplate.queryForList(
                "SELECT channel_session_id, COUNT(*) AS n FROM orchestrator.auth_journey WHERE lifecycle = 'STARTED' GROUP BY channel_session_id HAVING COUNT(*) > 1"
            ).forEach { add("I-3: channel ${it["CHANNEL_SESSION_ID"]} has ${it["N"]} running journeys") }
            // I-4: an authenticated channel carries evidence.
            jdbcTemplate.queryForList(
                "SELECT c.id FROM orchestrator.channel_session c LEFT JOIN orchestrator.auth_evidence e ON e.id = c.auth_evidence_id " +
                    "WHERE c.state = 'AUTHENTICATED' AND (e.id IS NULL OR LENGTH(CAST(e.amr_evidence AS VARCHAR)) <= 2)"
            ).forEach { add("I-4: channel ${it["ID"]} authenticated without evidence") }
            // I-13: at most one active password per account.
            jdbcTemplate.queryForList(
                "SELECT account_id, COUNT(*) AS n FROM account.auth_method WHERE method = 'password' AND active GROUP BY account_id HAVING COUNT(*) > 1"
            ).forEach { add("I-13: account ${it["ACCOUNT_ID"]} has ${it["N"]} active passwords") }
            // I-14: no device link to a deleted account.
            jdbcTemplate.queryForList(
                "SELECT l.binding_key_ref FROM orchestrator.device_account_link l LEFT JOIN account.account a ON a.id = l.account_id WHERE a.id IS NULL"
            ).forEach { add("I-14: device link ${it["BINDING_KEY_REF"]} points to a deleted account") }
        }
    }

    /** (status, body or null). Never throws for an HTTP status - 4xx is expected, 5xx is judged by the caller. */
    private fun call(method: HttpMethod, url: String, body: String? = null): Pair<Int, Map<String, Any?>?> =
        try {
            val response = restTemplate.exchange("http://localhost:$port$url", method, HttpEntity(body, headers()), mapType)
            response.statusCode.value() to response.body
        } catch (e: HttpStatusCodeException) {
            e.statusCode.value() to null
        }

    private fun serverError(result: Pair<Int, Map<String, Any?>?>): String? =
        if (result.first >= 500) "HTTP ${result.first}" else null

    @Suppress("UNCHECKED_CAST")
    private fun channelIdOf(body: Map<String, Any?>): String? = (body["channel"] as? Map<String, Any?>)?.get("channelSessionId") as? String

    @Suppress("UNCHECKED_CAST")
    private fun toolSessionOf(body: Map<String, Any?>?): String? = (body?.get("next") as? Map<String, Any?>)?.get("toolSessionId") as? String

    private fun wrongTan(tan: String) = if (tan == "000000") "000001" else "000000"

    /** Runs [steps] from a clean database; the first violation with the step index, or null. */
    private fun execute(steps: List<Step>): String? {
        resetDatabase()
        stubDpopWithFakeJwk(jwkThumbprintService)
        seedRegisteredAccount()
        val run = Run(deviceA = currentBindingKeyRef)
        steps.forEachIndexed { index, step ->
            run.perform(step)?.let { return "step $index ($step): $it" }
            run.violations().firstOrNull()?.let { return "step $index ($step): $it" }
        }
        return null
    }

    /** Drops steps one at a time as long as the run still fails - the shortest failing sequence. */
    private fun shrink(steps: List<Step>): Pair<List<Step>, String> {
        var current = steps
        var failure = checkNotNull(execute(current))
        var progress = true
        while (progress) {
            progress = false
            for (i in current.indices) {
                val candidate = current.filterIndexed { index, _ -> index != i }
                val result = execute(candidate) ?: continue
                current = candidate
                failure = result
                progress = true
                break
            }
        }
        return current to failure
    }

    init {
        given("random sequences of channel and journey actions") {
            then("no step ever breaks an invariant of docs/invarianten.md") {
                val failures = (1..SEEDS).mapNotNull { seed ->
                    val random = Random(seed)
                    val steps = listOf(Step.OPEN_CHANNEL) + List(STEPS_PER_RUN) { Step.entries[random.nextInt(Step.entries.size)] }
                    if (execute(steps) == null) return@mapNotNull null
                    val (minimal, failure) = shrink(steps)
                    "seed $seed: $failure - shortest sequence: $minimal"
                }
                failures.shouldBeEmpty()
            }

            then("the sequences it once found stay fixed - independent of which seeds happen to reach them") {
                listOf(
                    // S-1: a completed tool PATCH replayed after the logout re-authenticated the channel.
                    listOf(Step.OPEN_CHANNEL, Step.SIGN_IN_SMS, Step.LOG_OUT, Step.REPLAY_LAST_PATCH),
                    // M-5: method management, then a peer login - two running journeys on one channel.
                    listOf(
                        Step.OPEN_CHANNEL, Step.SIGN_IN_PASSWORD, Step.START_MANAGE, Step.SIGN_IN_SMS,
                        Step.CANCEL_JOURNEY, Step.START_MANAGE, Step.START_PEER_LOGIN
                    ),
                ).mapNotNull { steps -> execute(steps)?.let { "$steps: $it" } }.shouldBeEmpty()
            }
        }
    }

    private companion object {
        const val SEEDS = 200
        const val STEPS_PER_RUN = 14
    }
}
