package com.example.dpop.orchestrator.policy

import com.example.dpop.tool_spi.FactorType

/** What this session has already proven, read from the AuthContext (docs/04-orchestrierung.md #2). */
data class AuthEvidence(
    val amr: List<String>,
    val factorTypes: Set<FactorType>
)
