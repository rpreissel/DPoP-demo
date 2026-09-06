package com.example.dpop.orchestrator.session

/**
 * The profile-switchable half of `GET /channels/{id}/token` (DPoP-demo-xso) - `APP`-channel-only
 * by construction (`ChannelService.getToken` rejects `KEYCLOAK` channels before ever calling this;
 * that client already holds real Keycloak tokens from the standard browser login and refreshes
 * directly against Keycloak, never through the orchestrator). [MockTokenProvider] (default
 * profile) delegates to the existing [TokenService] mock; [KcTokenProvider] (`keycloak` profile)
 * mints a real, Keycloak-signed token via the custom account-token grant instead. Same two-
 * implementation-per-contract shape as `ChannelAccessGuard`/`DeviceChannelAccessGuard`/
 * `KcChannelAccessGuard` - [com.example.dpop.orchestrator.api.v1.channel.ChannelService] depends
 * on this interface only, never on either implementation directly.
 */
interface TokenProvider {
    fun tokenFor(channel: ChannelSession, minValiditySeconds: Long = TokenService.DEFAULT_MIN_VALIDITY_SECONDS): TokenPair
}
