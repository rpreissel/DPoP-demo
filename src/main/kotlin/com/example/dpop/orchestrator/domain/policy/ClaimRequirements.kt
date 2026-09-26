package com.example.dpop.orchestrator.domain.policy

import com.example.dpop.account.AccountProfile
import com.example.dpop.tool_spi.ClaimRequirement

/**
 * Whether [account] already carries what a tool declares it needs: the attribute established at
 * no less than the required trust level, counted over assertions minus retractions
 * (`AccountProfile.establishedClaims`, ADR-12).
 *
 * Generic on purpose. `ClaimRequirement(EMAIL, PROVEN)` - `enroll-password`'s gate - is one case of
 * this rule rather than its definition, which is what lets `ident-kvnr` require an attested
 * name/vorname/geburtsdatum without any tool knowing which procedure attested them. Shared by the
 * offer (`DefaultAuthPolicy`, `CandidateTools`) and the direct-activation defense
 * (`ToolControllerSupport.validatePreconditions`), so the gates cannot drift apart.
 */
internal fun requiresSatisfied(requirement: ClaimRequirement, account: AccountProfile?): Boolean {
    val established = account?.establishedClaims?.get(requirement.attributeType) ?: return false
    return established.rank >= requirement.minTrustLevel.rank
}
