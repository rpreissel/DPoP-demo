package com.example.dpop.orchestrator.channel

import com.example.dpop.orchestrator.kernel.ChannelType
import com.example.dpop.orchestrator.kernel.ErrorCode
import com.example.dpop.orchestrator.kernel.OrchestratorException
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.session.ChannelState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.UUID

/**
 * Token and ID claims exist only for an authenticated APP channel. A KEYCLOAK channel never has
 * an auth context - it must be refused as the wrong kind of channel (409), not fail on the
 * missing context (500).
 */
class ChannelServiceTokenAccessTest : BehaviorSpec({

    val channelSessionId = UUID.randomUUID()
    val guard = mockk<ChannelAccessGuard>()
    val service = ChannelService(
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), guard, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)
    )

    given("an authenticated KEYCLOAK channel") {
        every { guard.requireChannel(channelSessionId, any()) } returns
            ChannelSession(channel = ChannelType.KEYCLOAK).apply { state = ChannelState.AUTHENTICATED }

        then("the AccessToken is refused with 409") {
            shouldThrow<OrchestratorException> { service.getToken(channelSessionId, "kc", 30) }
                .code shouldBe ErrorCode.INVALID_STATE_TRANSITION
        }

        then("the ID claims are refused with 409") {
            shouldThrow<OrchestratorException> { service.getIdClaims(channelSessionId, "kc") }
                .code shouldBe ErrorCode.INVALID_STATE_TRANSITION
        }
    }
})
