package com.example.dpop.orchestrator.session

import com.example.dpop.orchestrator.journey.AuthJourneyRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Pure unit test of [RetentionJob] - the only thing worth verifying here is the ORDER-independent
 * fact its own class doc promises: expired [ChannelSession]s' `authContextId`s are collected
 * BEFORE those channels are deleted, and only ever passed on when there actually are any. A real
 * bug (collecting after the delete, or unconditionally calling deleteAllById on an empty list on
 * a repository that doesn't tolerate it) would silently orphan or crash retention.
 */
class RetentionJobTest : BehaviorSpec({

    fun job(channelSessionRepository: ChannelSessionRepository, authContextRepository: AuthContextRepository = mockk(relaxed = true)) = RetentionJob(
        toolSessionRepository = mockk(relaxed = true),
        journeyRepository = mockk<AuthJourneyRepository>(relaxed = true),
        channelSessionRepository = channelSessionRepository,
        authContextRepository = authContextRepository,
        sessionEventRepository = mockk(relaxed = true)
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
            every { channelSessionRepository.findByExpiresAtBefore(any()) } returns expired
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
            every { channelSessionRepository.findByExpiresAtBefore(any()) } returns listOf(ChannelSession().apply { authContextId = null })
            val authContextRepository = mockk<AuthContextRepository>(relaxed = true)

            job(channelSessionRepository, authContextRepository).cleanup()

            verify(exactly = 0) { authContextRepository.deleteAllById(any()) }
        }
    }

    given("no expired channels at all") {
        then("deleteAllById is never called - no orphaned AuthContext to name") {
            val channelSessionRepository = mockk<ChannelSessionRepository>(relaxed = true)
            every { channelSessionRepository.findByExpiresAtBefore(any()) } returns emptyList()
            val authContextRepository = mockk<AuthContextRepository>(relaxed = true)

            job(channelSessionRepository, authContextRepository).cleanup()

            verify(exactly = 0) { authContextRepository.deleteAllById(any()) }
        }
    }

    given("the channel-session retention cutoff") {
        then("is roughly 30 days in the past, not e.g. days-vs-hours confused with another repository's window") {
            val channelSessionRepository = mockk<ChannelSessionRepository>(relaxed = true)
            every { channelSessionRepository.findByExpiresAtBefore(any()) } returns emptyList()
            val cutoffSlot = slot<Instant>()

            job(channelSessionRepository).cleanup()

            verify { channelSessionRepository.findByExpiresAtBefore(capture(cutoffSlot)) }
            val expected = Instant.now().minus(Duration.ofDays(30))
            val drift = Duration.between(cutoffSlot.captured, expected).abs()
            (drift < Duration.ofMinutes(1)) shouldBe true
        }
    }
})
