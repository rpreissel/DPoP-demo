package com.example.dpop.orchestrator.api.v1

import com.example.dpop.orchestrator.kc.PeerAuthAssertion
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.session.SessionManagementService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.util.UUID

/** Pure unit test of [KcChannelAccessGuard] - the kc-anchor mismatch is the whole point of this class. */
class KcChannelAccessGuardTest : BehaviorSpec({

    fun channel(channelAnchor: String? = null) =
        ChannelSession(ChannelSession.Channel.KEYCLOAK, null, Instant.now().plusSeconds(3600)).apply {
            this.channelAnchor = channelAnchor
        }

    fun assertion(channelAnchor: String) =
        PeerAuthAssertion(
            jti = UUID.randomUUID().toString(),
            issuedAt = Instant.now(),
            channelAnchor = channelAnchor,
            subject = null
        )

    fun guardFor(channel: ChannelSession, id: UUID): KcChannelAccessGuard {
        val sessionManagementService = mockk<SessionManagementService> {
            every { findChannelSessionById(id) } returns channel
        }
        return KcChannelAccessGuard(sessionManagementService)
    }

    given("a channel anchored on channelAnchor") {
        val id = UUID.randomUUID()
        val channel = channel(channelAnchor = "anchor-1")

        `when`("the assertion claims the matching channelAnchor") {
            then("the channel is returned") {
                guardFor(channel, id).requireChannel(id, assertion(channelAnchor = "anchor-1")) shouldBe channel
            }
        }
        `when`("the assertion claims a different channelAnchor") {
            then("it is rejected as a binding mismatch") {
                shouldThrow<OrchestratorException> {
                    guardFor(channel, id).requireChannel(id, assertion(channelAnchor = "someone-elses-anchor"))
                }
            }
        }
    }

    given("no channel with this id exists") {
        then("it is rejected as not found") {
            val id = UUID.randomUUID()
            val sessionManagementService = mockk<SessionManagementService> {
                every { findChannelSessionById(id) } returns null
            }
            shouldThrow<OrchestratorException> {
                KcChannelAccessGuard(sessionManagementService).requireChannel(id, assertion(channelAnchor = "x"))
            }
        }
    }
})
