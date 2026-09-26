package com.example.dpop.orchestrator.domain

/**
 * The orchestrator's domain: what a developer reads to understand the rules - intents, levels,
 * the journey states and the transitions between them, the policy that prices evidence
 * (docs/adr/ADR-040-fachkern-im-paket-domain.md). Started as the shared vocabulary (`kernel`) that
 * broke the package cycles described below.
 *
 * Spring Modulith verifies the boundaries BETWEEN top-level modules (orchestrator, account,
 * auth_sms, ...), and `OrchestratorArchitectureTest` verifies layering rules WITHIN the
 * orchestrator. Neither noticed that the orchestrator's own packages had grown mutually dependent:
 * `session` <-> `policy`, `session` <-> `journey`, `session` <-> `journeytrace`, `kc` <-> `dpop`, and
 * `session` -> `api.v1` - the largest module in the system, and the one with no internal structure
 * anything checked.
 *
 * The cycles were not arbitrary. Almost every one of them was a NAME living in the wrong place:
 * `AuthIntent` is a word the whole orchestrator speaks, not a part of the journey machine;
 * `AmrSource` says where a piece of evidence came from, which is a policy question, not a
 * persistence one; `OrchestratorException` is how any layer refuses, not something the web layer
 * owns. Moving the vocabulary down here turns five cycles into plain one-way dependencies without
 * changing a single behaviour.
 *
 * This package and its subpackages therefore depend on nothing else inside the orchestrator and use
 * no framework - no Spring, JPA, Jackson or logging. That is the whole contract, and
 * `OrchestratorArchitectureTest` ("the orchestrator's domain") keeps it true.
 */
internal object DomainPackageMarker
