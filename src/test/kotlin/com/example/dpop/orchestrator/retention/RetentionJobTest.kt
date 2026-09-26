package com.example.dpop.orchestrator.retention

import com.example.dpop.orchestrator.session.AttemptThrottleRepository
import com.example.dpop.orchestrator.session.AuthContextRepository
import com.example.dpop.orchestrator.session.AuthEvidenceRepository
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.session.ChannelSessionRepository

import com.example.dpop.orchestrator.journey.AuthJourneyRepository
import com.example.dpop.orchestrator.journeytrace.JourneyTraceRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Pure unit test of [RetentionJob]: expired [ChannelSession]s' `authContextId`s are collected BEFORE
 * those channels are deleted, and only ever passed on when there actually are any - a real bug
 * would silently orphan or crash retention. Real repositories' semantics are mocked; every
 * assertion is about what actually gets deleted.
 */
class RetentionJobTest : BehaviorSpec({

    fun job(
        channelSessionRepository: ChannelSessionRepository,
        authContextRepository: AuthContextRepository = mockk(relaxed = true),
        authEvidenceRepository: AuthEvidenceRepository = mockk(relaxed = true),
        journeyTraceRepository: JourneyTraceRepository = mockk(relaxed = true),
        attemptThrottleRepository: AttemptThrottleRepository = mockk(relaxed = true),
    ) = RetentionJob(
        toolSessionRepository = mockk(relaxed = true),
        journeyRepository = mockk<AuthJourneyRepository>(relaxed = true),
        channelSessionRepository = channelSessionRepository,
        authContextRepository = authContextRepository,
        authEvidenceRepository = authEvidenceRepository,
        journeyTraceRepository = journeyTraceRepository,
        attemptThrottleRepository = attemptThrottleRepository
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
            verify { authContextRepository.deleteAllByIdInBatch(capture(idsSlot)) }
            idsSlot.captured shouldContainExactlyInAnyOrder listOf(authContextId1, authContextId2)
            verify { channelSessionRepository.deleteAllInBatch(expired) }
        }
    }

    given("expired channels with no AuthContext at all") {
        then("deleteAllById is never called - nothing to orphan, no pointless empty-list call") {
            val channelSessionRepository = mockk<ChannelSessionRepository>(relaxed = true)
            every { channelSessionRepository.findByExpiresAtBefore(any(), any()) } returnsMany
                listOf(listOf(ChannelSession().apply { authContextId = null }), emptyList())
            val authContextRepository = mockk<AuthContextRepository>(relaxed = true)

            job(channelSessionRepository, authContextRepository).cleanup()

            verify(exactly = 0) { authContextRepository.deleteAllByIdInBatch(any()) }
        }
    }

    given("no expired channels at all") {
        then("deleteAllById is never called - no orphaned AuthContext to name") {
            val channelSessionRepository = mockk<ChannelSessionRepository>(relaxed = true)
            every { channelSessionRepository.findByExpiresAtBefore(any(), any()) } returns emptyList()
            val authContextRepository = mockk<AuthContextRepository>(relaxed = true)

            job(channelSessionRepository, authContextRepository).cleanup()

            verify(exactly = 0) { authContextRepository.deleteAllByIdInBatch(any()) }
        }
    }

    given("the channel-session retention cutoff") {
        then("is roughly 14 days in the past - as long as the journey trace it serves, not e.g. days-vs-hours confused with another repository's window") {
            val channelSessionRepository = mockk<ChannelSessionRepository>(relaxed = true)
            every { channelSessionRepository.findByExpiresAtBefore(any(), any()) } returns emptyList()
            val cutoffSlot = slot<Instant>()

            job(channelSessionRepository).cleanup()

            verify { channelSessionRepository.findByExpiresAtBefore(capture(cutoffSlot), any()) }
            val expected = Instant.now().minus(Duration.ofDays(14))
            val drift = Duration.between(cutoffSlot.captured, expected).abs()
            (drift < Duration.ofMinutes(1)) shouldBe true
        }
    }

    given("a retention run over the two age-swept tables that hang off no foreign key") {
        then("the journey trace is swept by age, and stale throttle counters with it (B3)") {
            val channelSessionRepository = mockk<ChannelSessionRepository>(relaxed = true)
            every { channelSessionRepository.findByExpiresAtBefore(any(), any()) } returns emptyList()
            val journeyTraceRepository = mockk<JourneyTraceRepository>(relaxed = true)
            val attemptThrottleRepository = mockk<AttemptThrottleRepository>(relaxed = true)
            val logCutoff = slot<Instant>()
            val throttleCutoff = slot<Instant>()
            val throttleNow = slot<Instant>()
            every { journeyTraceRepository.deleteByCreatedAtBefore(capture(logCutoff)) } returns 0
            every { attemptThrottleRepository.deleteStaleCounters(capture(throttleCutoff), capture(throttleNow)) } returns 0

            val before = Instant.now()
            job(
                channelSessionRepository,
                journeyTraceRepository = journeyTraceRepository,
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
