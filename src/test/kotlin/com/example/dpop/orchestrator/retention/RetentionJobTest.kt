package com.example.dpop.orchestrator.retention

import com.example.dpop.orchestrator.kernel.ChannelType
import com.example.dpop.orchestrator.session.AttemptThrottleRepository
import com.example.dpop.orchestrator.session.AuthContextRepository
import com.example.dpop.orchestrator.session.AuthEvidenceRepository
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.session.ChannelSessionRepository

import com.example.dpop.orchestrator.journey.AuthJourneyRepository
import com.example.dpop.orchestrator.journeylog.JourneyLogRepository
import com.example.dpop.orchestrator.kc.KeycloakAdminClient
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.beans.factory.ObjectProvider
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Pure unit test of [RetentionJob]. Covers two independent things: the ORDER-independent fact its
 * own class doc promises (expired [ChannelSession]s' `authContextId`s are collected BEFORE those
 * channels are deleted, and only ever passed on when there actually are any - a real bug would
 * silently orphan or crash retention), and the `KEYCLOAK`-only early-cleanup path
 * (DPoP-demo-f9o.12): a channel is only ever deleted ahead of the normal retention window when
 * Keycloak affirmatively confirms its session is gone, never on "don't know".
 */
class RetentionJobTest : BehaviorSpec({

    // Not `mockk(relaxed = true)`: ObjectProvider<T>'s generic, type-erased getIfAvailable()
    // confuses relaxed mocking into returning a plain Object instead of null, which then blows up
    // with a ClassCastException the moment RetentionJob tries to use it as a KeycloakAdminClient?.
    fun noKeycloakClient(): ObjectProvider<KeycloakAdminClient> = mockk { every { getIfAvailable() } returns null }

    fun job(
        channelSessionRepository: ChannelSessionRepository,
        authContextRepository: AuthContextRepository = mockk(relaxed = true),
        authEvidenceRepository: AuthEvidenceRepository = mockk(relaxed = true),
        journeyLogRepository: JourneyLogRepository = mockk(relaxed = true),
        attemptThrottleRepository: AttemptThrottleRepository = mockk(relaxed = true),
        keycloakAdminClient: ObjectProvider<KeycloakAdminClient> = noKeycloakClient()
    // The deleting moved into SessionRetentionSweeper so that RetentionJob itself can stay
    // non-transactional while it probes Keycloak (see RetentionJob's own doc). A REAL sweeper is
    // wired here rather than a mock: every assertion below is about what actually gets deleted, so
    // stubbing it out would leave this test verifying nothing.
    ) = RetentionJob(
        sweeper = SessionRetentionSweeper(
            toolSessionRepository = mockk(relaxed = true),
            journeyRepository = mockk<AuthJourneyRepository>(relaxed = true),
            channelSessionRepository = channelSessionRepository,
            authContextRepository = authContextRepository,
            authEvidenceRepository = authEvidenceRepository,
            journeyLogRepository = journeyLogRepository,
            attemptThrottleRepository = attemptThrottleRepository
        ),
        channelSessionRepository = channelSessionRepository,
        keycloakAdminClient = keycloakAdminClient
    )

    given("expired channels that each carry an AuthContext") {
        then("their authContextIds are deleted too - collected before the channels themselves are gone") {
            val authContextId1 = UUID.randomUUID()
            val authContextId2 = UUID.randomUUID()
            val expired = listOf(
                ChannelSession().apply { authContextId = authContextId1 },
                ChannelSession().apply { authContextId = authContextId2 }
            )
            val channelSessionRepository = mockk<ChannelSessionRepository>(relaxed = true)
            every { channelSessionRepository.findByExpiresAtBefore(any(), any()) } returnsMany listOf(expired, emptyList())
            val authContextRepository = mockk<AuthContextRepository>(relaxed = true)

            job(channelSessionRepository, authContextRepository).cleanup()

            val idsSlot = slot<List<UUID>>()
            verify { authContextRepository.deleteAllById(capture(idsSlot)) }
            idsSlot.captured shouldContainExactlyInAnyOrder listOf(authContextId1, authContextId2)
            verify { channelSessionRepository.deleteAll(expired) }
        }
    }

    given("expired channels with no AuthContext at all") {
        then("deleteAllById is never called - nothing to orphan, no pointless empty-list call") {
            val channelSessionRepository = mockk<ChannelSessionRepository>(relaxed = true)
            every { channelSessionRepository.findByExpiresAtBefore(any(), any()) } returnsMany
                listOf(listOf(ChannelSession().apply { authContextId = null }), emptyList())
            val authContextRepository = mockk<AuthContextRepository>(relaxed = true)

            job(channelSessionRepository, authContextRepository).cleanup()

            verify(exactly = 0) { authContextRepository.deleteAllById(any()) }
        }
    }

    given("no expired channels at all") {
        then("deleteAllById is never called - no orphaned AuthContext to name") {
            val channelSessionRepository = mockk<ChannelSessionRepository>(relaxed = true)
            every { channelSessionRepository.findByExpiresAtBefore(any(), any()) } returns emptyList()
            val authContextRepository = mockk<AuthContextRepository>(relaxed = true)

            job(channelSessionRepository, authContextRepository).cleanup()

            verify(exactly = 0) { authContextRepository.deleteAllById(any()) }
        }
    }

    given("the channel-session retention cutoff") {
        then("is roughly 30 days in the past, not e.g. days-vs-hours confused with another repository's window") {
            val channelSessionRepository = mockk<ChannelSessionRepository>(relaxed = true)
            every { channelSessionRepository.findByExpiresAtBefore(any(), any()) } returns emptyList()
            val cutoffSlot = slot<Instant>()

            job(channelSessionRepository).cleanup()

            verify { channelSessionRepository.findByExpiresAtBefore(capture(cutoffSlot), any()) }
            val expected = Instant.now().minus(Duration.ofDays(30))
            val drift = Duration.between(cutoffSlot.captured, expected).abs()
            (drift < Duration.ofMinutes(1)) shouldBe true
        }
    }

    given("an expired KEYCLOAK channel whose Keycloak session is confirmed gone") {
        then("deletes it immediately - no need to wait out the full 30-day retention") {
            val kcChannel = ChannelSession(channel = ChannelType.KEYCLOAK).apply {
                channelSessionId = UUID.randomUUID()
                accountId = 42L
                durableKcSessionId = "kc-session-1"
            }
            val channelSessionRepository = mockk<ChannelSessionRepository>(relaxed = true)
            every { channelSessionRepository.findByExpiresAtBefore(any(), any()) } returns emptyList()
            every { channelSessionRepository.findByChannelAndExpiresAtBefore(ChannelType.KEYCLOAK, any()) } returns listOf(kcChannel)
            val client = mockk<KeycloakAdminClient>()
            every { client.isSessionAlive(42L, "kc-session-1") } returns false
            val provider = mockk<ObjectProvider<KeycloakAdminClient>> { every { getIfAvailable() } returns client }

            job(channelSessionRepository, keycloakAdminClient = provider).cleanup()

            verify { channelSessionRepository.deleteAll(listOf(kcChannel)) }
        }
    }

    given("an expired KEYCLOAK channel whose Keycloak session is still alive, or whose status is unknown") {
        then("is never deleted early - only the confirmed-dead case skips the normal retention window") {
            val stillAlive = ChannelSession(channel = ChannelType.KEYCLOAK).apply {
                channelSessionId = UUID.randomUUID(); accountId = 1L; durableKcSessionId = "alive"
            }
            val unknownAccount = ChannelSession(channel = ChannelType.KEYCLOAK).apply {
                channelSessionId = UUID.randomUUID(); accountId = 2L; durableKcSessionId = "unknown"
            }
            val noDurableIdYet = ChannelSession(channel = ChannelType.KEYCLOAK).apply {
                channelSessionId = UUID.randomUUID(); accountId = 3L; durableKcSessionId = null
            }
            val channelSessionRepository = mockk<ChannelSessionRepository>(relaxed = true)
            every { channelSessionRepository.findByExpiresAtBefore(any(), any()) } returns emptyList()
            every { channelSessionRepository.findByChannelAndExpiresAtBefore(ChannelType.KEYCLOAK, any()) } returns
                listOf(stillAlive, unknownAccount, noDurableIdYet)
            val client = mockk<KeycloakAdminClient>()
            every { client.isSessionAlive(1L, "alive") } returns true
            every { client.isSessionAlive(2L, "unknown") } returns null
            val provider = mockk<ObjectProvider<KeycloakAdminClient>> { every { getIfAvailable() } returns client }

            job(channelSessionRepository, keycloakAdminClient = provider).cleanup()

            verify(exactly = 0) { channelSessionRepository.deleteAll(any()) }
        }
    }

    given("no Keycloak Admin client configured (e.g. the default, non-keycloak profile)") {
        then("skips the early-cleanup check entirely and never queries for KEYCLOAK candidates") {
            val channelSessionRepository = mockk<ChannelSessionRepository>(relaxed = true)
            every { channelSessionRepository.findByExpiresAtBefore(any(), any()) } returns emptyList()

            job(channelSessionRepository).cleanup()

            verify(exactly = 0) { channelSessionRepository.findByChannelAndExpiresAtBefore(any(), any()) }
        }
    }

    given("a retention run over the two age-swept tables that hang off no foreign key") {
        then("the journey log is swept by age, and stale throttle counters with it (B3)") {
            val channelSessionRepository = mockk<ChannelSessionRepository>(relaxed = true)
            every { channelSessionRepository.findByExpiresAtBefore(any(), any()) } returns emptyList()
            val journeyLogRepository = mockk<JourneyLogRepository>(relaxed = true)
            val attemptThrottleRepository = mockk<AttemptThrottleRepository>(relaxed = true)
            val logCutoff = slot<Instant>()
            val throttleCutoff = slot<Instant>()
            val throttleNow = slot<Instant>()
            every { journeyLogRepository.deleteByCreatedAtBefore(capture(logCutoff)) } returns 0
            every { attemptThrottleRepository.deleteStaleCounters(capture(throttleCutoff), capture(throttleNow)) } returns 0

            val before = Instant.now()
            job(
                channelSessionRepository,
                journeyLogRepository = journeyLogRepository,
                attemptThrottleRepository = attemptThrottleRepository
            ).cleanup()

            // Both cutoffs are ages, not "now" - a sweep that passed the current instant would
            // delete the whole table including counters whose lock still runs.
            logCutoff.captured shouldBeLessThan before.minus(Duration.ofDays(13))
            throttleCutoff.captured shouldBeLessThan before.minus(Duration.ofDays(6))
            // The longest lockout any throttle service uses is 15 minutes; the retention window
            // must stay far beyond it so a sweep can never shorten an active budget.
            throttleCutoff.captured shouldBeLessThan throttleNow.captured.minus(Duration.ofHours(1))
        }
    }
})
