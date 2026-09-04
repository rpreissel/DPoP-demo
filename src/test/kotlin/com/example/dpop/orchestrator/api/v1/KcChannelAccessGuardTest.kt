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

    fun channel(kcAuthSessionId: String? = null, kcSessionId: String? = null) =
        ChannelSession(ChannelSession.Channel.KEYCLOAK, null, Instant.now().plusSeconds(3600)).apply {
            this.kcAuthSessionId = kcAuthSessionId
            this.kcSessionId = kcSessionId
        }

    fun assertion(kcAuthSessionId: String? = null, kcSessionId: String? = null) =
        PeerAuthAssertion(
            jti = UUID.randomUUID().toString(),
            issuedAt = Instant.now(),
            kcAuthSessionId = kcAuthSessionId,
            kcSessionId = kcSessionId,
            subject = null
        )

    fun guardFor(channel: ChannelSession, id: UUID): KcChannelAccessGuard {
        val sessionManagementService = mockk<SessionManagementService> {
            every { findChannelSessionById(id) } returns channel
        }
        return KcChannelAccessGuard(sessionManagementService)
    }

    given("an initial-login channel anchored on kcAuthSessionId") {
        val id = UUID.randomUUID()
        val channel = channel(kcAuthSessionId = "auth-session-1")

        `when`("the assertion claims the matching kcAuthSessionId") {
            then("the channel is returned") {
                guardFor(channel, id).requireChannel(id, assertion(kcAuthSessionId = "auth-session-1")) shouldBe channel
            }
        }
        `when`("the assertion claims a different kcAuthSessionId") {
            then("it is rejected as a binding mismatch") {
                shouldThrow<OrchestratorException> {
                    guardFor(channel, id).requireChannel(id, assertion(kcAuthSessionId = "someone-elses-session"))
                }
            }
        }
        `when`("the assertion carries kcSessionId instead (step-up shape on an initial-login channel)") {
            then("it is rejected - the anchor kinds don't match") {
                shouldThrow<OrchestratorException> {
                    guardFor(channel, id).requireChannel(id, assertion(kcSessionId = "auth-session-1"))
                }
            }
        }
    }

    given("a step-up channel anchored on kcSessionId") {
        val id = UUID.randomUUID()
        val channel = channel(kcSessionId = "user-session-7")

        `when`("the assertion claims the matching kcSessionId") {
            then("the channel is returned") {
                guardFor(channel, id).requireChannel(id, assertion(kcSessionId = "user-session-7")) shouldBe channel
            }
        }
        `when`("the assertion claims a different kcSessionId") {
            then("it is rejected as a binding mismatch") {
                shouldThrow<OrchestratorException> {
                    guardFor(channel, id).requireChannel(id, assertion(kcSessionId = "a-different-user-session"))
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
                KcChannelAccessGuard(sessionManagementService).requireChannel(id, assertion(kcAuthSessionId = "x"))
            }
        }
    }
})
