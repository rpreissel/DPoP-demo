package com.example.dpop.orchestrator.domain

/**
 * Who proved a method: the orchestrator's own tool, or a native Keycloak authenticator
 * (docs/05-api.md Abschnitt 3).
 *
 * Kernel vocabulary, not a persistence detail: `orchestrator.policy`, whose whole job is judging
 * evidence, names its origin without importing the persistence package (`orchestrator.session`,
 * where the JPA entity storing it lives) - that import would close a package cycle.
 */
object AmrSource {
    const val ORCHESTRATOR = "orchestrator"
    const val KEYCLOAK = "kc"
}
