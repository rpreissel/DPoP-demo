package com.example.dpop.account.domain

import com.example.dpop.texts.Text
import com.example.dpop.tool_api.AnchorRule
import com.example.dpop.tool_api.IdentityConflictException
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.AttributeType

/**
 * What writing an anchor value means for one account - the anchor is the lookup authority every
 * lookup-login resolves through, so this is where a takeover would happen. Pure: `AnchorRegistry`
 * reads the current rows, asks here, and writes (docs/ideen/fachkern-und-technik-trennen.md).
 */
sealed interface AnchorDecision {
    /** This account already holds exactly this value - nothing to write. */
    data object AlreadyHeld : AnchorDecision

    /** First binding of a value for this type on this account. */
    data object Establish : AnchorDecision

    /**
     * This account's own anchor gets a new value, updated in place. [retractFromLog] is the replaced
     * value in the claim log's terms, to be withdrawn so the log agrees with the anchor (ADR-19) -
     * `null` when old and new are the same value there (a case-preserving pseudonym replaced by one
     * differing only in case): the retraction, stamped with the new claim's time, would otherwise
     * void the claim just being set (review 2026-09, Phase F).
     */
    data class Replace(val retractFromLog: String?) : AnchorDecision

    companion object {
        /**
         * - Held by ANOTHER account: refused outright, never re-assigned - ADR-11 makes a
         *   cross-account conflict an upstream rejection, and the refusal rolls the whole claim back.
         * - A new value for this account's own anchor: only if [rule] allows replacement (`EMAIL`);
         *   an immutable anchor (`PERSON_ID`) is refused the same way.
         * - Both writes are priced by [AnchorRule.acrFloor] and refused below it: establishing binds a
         *   value to an account, replacing re-points one that other lookups already resolve through.
         *   Refused rather than silently logged without the anchor - a caller that believed it bound an
         *   identity must not proceed on a false premise.
         *
         * @param heldBy the account that holds [value] as its [type] anchor today, if any.
         * @param currentValue this account's current [type] anchor value, if any.
         */
        fun decide(
            accountId: Long,
            type: AttributeType,
            value: String,
            heldBy: Long?,
            currentValue: String?,
            rule: AnchorRule,
            provenAcr: AcrLevel,
        ): AnchorDecision {
            if (heldBy != null) {
                if (heldBy == accountId) return AlreadyHeld
                throw IdentityConflictException(
                    Text("Dieser {type}-Wert gehoert bereits zu einem anderen Konto", "type" to type.wireName),
                    "${type.wireName} anchor already held by account $heldBy, rejected for account $accountId",
                )
            }
            if (currentValue == null) {
                requirePaid(type, provenAcr, rule.acrFloor.establish, replacing = false)
                return Establish
            }
            if (!rule.allowsReplacement) {
                throw IdentityConflictException(
                    Text("Dieser {type}-Wert kann fuer dieses Konto nicht mehr geaendert werden", "type" to type.wireName),
                    "${type.wireName} for account $accountId is immutable",
                )
            }
            requirePaid(type, provenAcr, rule.acrFloor.replace, replacing = true)
            val replaced = normalizeClaimValue(type, currentValue)
            return Replace(retractFromLog = replaced.takeIf { it != normalizeClaimValue(type, value) })
        }

        private fun requirePaid(type: AttributeType, provenAcr: AcrLevel, floor: AcrLevel, replacing: Boolean) {
            if (AcrLevel.rank(provenAcr) >= AcrLevel.rank(floor)) return
            throw IdentityConflictException(
                // Two sentences, not one with the verb as a value: a verb is wording, every language inflects it itself.
                if (replacing) {
                    Text("Dieser {type}-Wert kann erst ab {floor} ersetzt werden, nachgewiesen ist {provenAcr}", "type" to type.wireName, "floor" to floor.value, "provenAcr" to provenAcr.value)
                } else {
                    Text("Dieser {type}-Wert kann erst ab {floor} gesetzt werden, nachgewiesen ist {provenAcr}", "type" to type.wireName, "floor" to floor.value, "provenAcr" to provenAcr.value)
                },
                "${type.wireName} ${if (replacing) "replace" else "establish"} needs ${floor.value}, session proved ${provenAcr.value}",
            )
        }
    }
}
