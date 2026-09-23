package com.example.dpop.orchestrator.kernel

/**
 * Who proved a method: the orchestrator's own tool, or a native Keycloak authenticator
 * (docs/05-api.md Abschnitt 3).
 *
 * Kernel vocabulary, not a persistence detail. It used to live next to the JPA entity that stores
 * it, in `orchestrator.session` - which meant `orchestrator.policy`, whose whole job is judging
 * evidence, had to import the persistence package to name its origin. That single import was one
 * of the orchestrator's package cycles; the word simply lived below its meaning.
 */
object AmrSource {
    const val ORCHESTRATOR = "orchestrator"
    const val KEYCLOAK = "kc"
}
