package com.example.dpop.orchestrator.kernel

/**
 * The orchestrator's own shared vocabulary - the handful of types that several of its packages
 * need to speak about at all, and that therefore belong under all of them rather than inside one.
 *
 * Spring Modulith verifies the boundaries BETWEEN top-level modules (orchestrator, account,
 * auth_sms, ...), and `OrchestratorArchitectureTest` verifies layering rules WITHIN the
 * orchestrator. Neither noticed that the orchestrator's own packages had grown mutually dependent:
 * `session` <-> `policy`, `session` <-> `journey`, `session` <-> `journeylog`, `kc` <-> `dpop`, and
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
 * This package therefore depends on NOTHING inside the orchestrator. That is its whole contract,
 * and `OrchestratorArchitectureTest` ("the orchestrator's own packages") keeps it true.
 */
internal object KernelPackageMarker
