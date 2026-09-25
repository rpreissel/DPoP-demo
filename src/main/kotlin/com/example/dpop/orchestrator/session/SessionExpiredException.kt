package com.example.dpop.orchestrator.session

/**
 * The login behind a channel is over: its refresh window lapsed (idle), or Keycloak refused the
 * refresh (its session ended). Never answered by quietly issuing new tokens - that used to keep a
 * channel alive for its full 24 hours without any new proof (review 2026-09, M-4). The caller ends
 * the channel; the client signs in again.
 */
class SessionExpiredException(message: String) : RuntimeException(message)
