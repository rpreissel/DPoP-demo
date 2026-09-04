package com.example.dpop.orchestrator.api.v1

import com.example.dpop.orchestrator.kc.PeerAuthAssertion
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.session.SessionManagementService
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.UUID

/**
 * "Wer spricht hier, und darf er auf diesen Kanal?" (docs/ideen/web-keycloak-kanal.md #4) - one
 * contract per facade rather than a single shared method, because the proof shape genuinely
 * differs (a bare device thumbprint vs. a verified Keycloak peer-auth assertion) and Kotlin has
 * no union type to unify them with. Both follow the same shape (`requireChannel(id, proof) ->
 * ChannelSession`, same [OrchestratorException] semantics on mismatch) and are deliberately cut
 * so a later change to either facade's proof mechanism (e.g. mTLS instead of DPoP) touches
 * exactly one implementation, never the channel resource itself.
 */
interface ChannelAccessGuard {
    fun requireChannel(channelSessionId: UUID, bindingKeyRef: String): ChannelSession
}

/**
 * Both facades' implementation for the facade-neutral tool endpoints (docs/09-dpop.md #3,
 * docs/ideen/web-keycloak-kanal.md #6): those endpoints are shared, so this single guard must
 * accept whichever proof shape [DpopBindingKeyResolver] resolved - a bare DPoP thumbprint (App) or
 * a `"kc:"`-prefixed kc-anchor (Web). The kc entry point itself (`KcChannelService`) still goes
 * through [KcChannelAccessGuard] directly, with the strongly-typed [PeerAuthAssertion] rather than
 * this string encoding - that path already has the assertion in hand and doesn't need it flattened.
 */
@Component
class DeviceChannelAccessGuard(
    private val sessionManagementService: SessionManagementService
) : ChannelAccessGuard {

    override fun requireChannel(channelSessionId: UUID, bindingKeyRef: String): ChannelSession {
        val channel = sessionManagementService.findChannelSessionById(channelSessionId)
            ?: throw OrchestratorException.notFound("Channel session not found: $channelSessionId")
        val matches = if (bindingKeyRef.startsWith(KC_ANCHOR_PREFIX)) {
            val presented = bindingKeyRef.removePrefix(KC_ANCHOR_PREFIX)
            val expected = channel.kcSessionId ?: channel.kcAuthSessionId
            constantTimeEquals(expected, presented)
        } else {
            // Constant-time, though both sides are public thumbprints rather than secrets - the
            // cheapest way to keep this from becoming one if the binding ever carries more.
            constantTimeEquals(channel.bindingKeyRef, bindingKeyRef)
        }
        if (!matches) {
            throw OrchestratorException.bindingMismatch("Caller proof does not match this channel")
        }
        return channel
    }

    private fun constantTimeEquals(stored: String?, presented: String?): Boolean {
        if (stored == null || presented == null) return false
        return MessageDigest.isEqual(stored.toByteArray(), presented.toByteArray())
    }

    companion object {
        const val KC_ANCHOR_PREFIX = "kc:"
    }
}

/**
 * WEB implementation (docs/ideen/web-keycloak-kanal.md #2/#4): the kc-anchor alone never
 * authorizes anything - Keycloak's peer-auth assertion must independently claim the same
 * `kcAuthSessionId`/`kcSessionId` this channel was opened with, otherwise a leaked
 * `channelSessionId` plus any validly signed Keycloak assertion would be enough to hijack it.
 */
@Component
class KcChannelAccessGuard(
    private val sessionManagementService: SessionManagementService
) {

    fun requireChannel(channelSessionId: UUID, assertion: PeerAuthAssertion): ChannelSession {
        val channel = sessionManagementService.findChannelSessionById(channelSessionId)
            ?: throw OrchestratorException.notFound("Channel session not found: $channelSessionId")
        val matches = when {
            assertion.kcSessionId != null -> constantTimeEquals(channel.kcSessionId, assertion.kcSessionId)
            assertion.kcAuthSessionId != null -> constantTimeEquals(channel.kcAuthSessionId, assertion.kcAuthSessionId)
            else -> false
        }
        if (!matches) {
            throw OrchestratorException.bindingMismatch("Keycloak assertion does not match this channel's kc-anchor")
        }
        return channel
    }

    private fun constantTimeEquals(stored: String?, presented: String?): Boolean {
        if (stored == null || presented == null) return false
        return MessageDigest.isEqual(stored.toByteArray(), presented.toByteArray())
    }
}
