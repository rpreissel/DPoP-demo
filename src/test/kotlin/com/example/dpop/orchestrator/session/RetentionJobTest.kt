package com.example.dpop.orchestrator.session

import com.example.dpop.orchestrator.journey.AuthJourneyRepository
import com.example.dpop.orchestrator.kc.KeycloakAdminClient
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
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
        keycloakAdminClient: ObjectProvider<KeycloakAdminClient> = noKeycloakClient()
    ) = RetentionJob(
        toolSessionRepository = mockk(relaxed = true),
        journeyRepository = mockk<AuthJourneyRepository>(relaxed = true),
        channelSessionRepository = channelSessionRepository,
        authContextRepository = authContextRepository,
        authEvidenceRepository = authEvidenceRepository,
        sessionEventRepository = mockk(relaxed = true),
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

    given("an expired KEYCLOAK channel whose Keycloak session is confirmed gone") {
        then("deletes it immediately - no need to wait out the full 30-day retention") {
            val kcChannel = ChannelSession(channel = ChannelSession.Channel.KEYCLOAK).apply {
                channelSessionId = UUID.randomUUID()
                accountId = 42L
                durableKcSessionId = "kc-session-1"
            }
            val channelSessionRepository = mockk<ChannelSessionRepository>(relaxed = true)
            every { channelSessionRepository.findByExpiresAtBefore(any()) } returns emptyList()
            every { channelSessionRepository.findByChannelAndExpiresAtBefore(ChannelSession.Channel.KEYCLOAK, any()) } returns listOf(kcChannel)
            val client = mockk<KeycloakAdminClient>()
            every { client.isSessionAlive(42L, "kc-session-1") } returns false
            val provider = mockk<ObjectProvider<KeycloakAdminClient>> { every { getIfAvailable() } returns client }

            job(channelSessionRepository, keycloakAdminClient = provider).cleanup()

            verify { channelSessionRepository.deleteAll(listOf(kcChannel)) }
        }
    }

    given("an expired KEYCLOAK channel whose Keycloak session is still alive, or whose status is unknown") {
        then("is never deleted early - only the confirmed-dead case skips the normal retention window") {
            val stillAlive = ChannelSession(channel = ChannelSession.Channel.KEYCLOAK).apply {
                channelSessionId = UUID.randomUUID(); accountId = 1L; durableKcSessionId = "alive"
            }
            val unknownAccount = ChannelSession(channel = ChannelSession.Channel.KEYCLOAK).apply {
                channelSessionId = UUID.randomUUID(); accountId = 2L; durableKcSessionId = "unknown"
            }
            val noDurableIdYet = ChannelSession(channel = ChannelSession.Channel.KEYCLOAK).apply {
                channelSessionId = UUID.randomUUID(); accountId = 3L; durableKcSessionId = null
            }
            val channelSessionRepository = mockk<ChannelSessionRepository>(relaxed = true)
            every { channelSessionRepository.findByExpiresAtBefore(any()) } returns emptyList()
            every { channelSessionRepository.findByChannelAndExpiresAtBefore(ChannelSession.Channel.KEYCLOAK, any()) } returns
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
            every { channelSessionRepository.findByExpiresAtBefore(any()) } returns emptyList()

            job(channelSessionRepository).cleanup()

            verify(exactly = 0) { channelSessionRepository.findByChannelAndExpiresAtBefore(any(), any()) }
        }
    }
})
