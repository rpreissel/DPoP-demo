package com.example.dpop.orchestrator.session

import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/** Default-profile [TokenProvider]: the existing fully-mocked [TokenService], unchanged, for every channel. */
@Service
@Profile("!keycloak")
class MockTokenProvider(
    private val tokenService: TokenService
) : TokenProvider {
    override fun tokenFor(channel: ChannelSession, minValiditySeconds: Long): TokenPair =
        tokenService.tokenFor(channel.authContextId!!, minValiditySeconds)
}
