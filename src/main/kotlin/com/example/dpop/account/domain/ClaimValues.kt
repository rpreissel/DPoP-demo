package com.example.dpop.account.domain

import com.example.dpop.tool_spi.AttributeType
import java.util.UUID

/**
 * The one place the claim log's normalization rule exists: trimmed, lowercased - except for the
 * card pseudonyms, opaque values in which case matters. Their anchor keeps the case
 * (`normalizeAnchorValue`), so the log must too; lowercasing them once made two different pseudonyms
 * one value in the log (review 2026-09, Phase F). The write-time hook of `AccountClaim` and the
 * anti-join that subtracts retractions both depend on it, or a withdrawn value silently keeps
 * counting (ADR-12).
 */
fun normalizeClaimValue(type: AttributeType?, value: String?): String? =
    value?.trim()?.let { if (type in CASE_PRESERVING) it else it.lowercase() }

private val CASE_PRESERVING = setOf(AttributeType.EID_RESTRICTED_ID, AttributeType.NECT_RESTRICTED_ID)

/**
 * What makes a claim already logged: same attribute, same normalized value, same source, same method
 * instance. The claim log is a change log, not a run log - re-attesting the same card eight times
 * must not cost eight rows. The method instance is part of the key so a fresh enrollment of a known
 * value still logs: revoking the OLD instance retracts only what THAT instance asserted.
 */
data class ClaimKey(val type: AttributeType, val normalizedValue: String, val source: String, val authMethodId: UUID?)
