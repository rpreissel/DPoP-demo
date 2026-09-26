package com.example.dpop.orchestrator.domain

/**
 * Which facade a channel came through: the DPoP-bound App channel or the Keycloak (Web) channel.
 * Vocabulary, not session state - it lives here so that per-channel settings (tool availability
 * and order) can speak about it without depending on the session package.
 */
enum class ChannelType {
    APP, KEYCLOAK
}
