package com.example.dpop.tool_api

import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.EnrollmentRef

/**
 * Read-only account and credential lookups for a tool controller.
 *
 * Inject this directly into a tool controller's constructor. Results are opaque ids and
 * enrollment references only - never account profile data.
 */
interface AccountDirectory {
    /**
     * Resolves the account that has [email] as its confirmed address.
     *
     * @return the account id, or `null` if no account has this address confirmed.
     */
    fun resolveAccountByEmail(email: String): Long?

    /**
     * Resolves the account that has established [value] as its anchor of [type] - the generic
     * resolve-identity read of the claims model. Lookup runs on the attribute type's normalized
     * form, exactly like the write side does. Generalizes [resolveAccountByEmail], which stays
     * as the typed convenience for the one anchor wired today. [type] must be an anchor
     * attribute (`type.anchorBindingStrength != null`).
     *
     * @return the account id, or `null` if no account has established this anchor.
     */
    fun resolveByAnchor(type: AttributeType, value: String): Long?

    /**
     * The account's established value for anchor [type], in the attribute type's normalized
     * form - the reverse read of [resolveByAnchor]: what that one resolves BY, per account.
     * `null` if this account has never established the anchor, or its latest claim on it
     * collided with another account's anchor (first writer wins there).
     */
    fun anchorValue(accountId: Long, type: AttributeType): String?

    /**
     * The account's currently active credential for [method] (e.g. `"sms"`, `"password"`).
     *
     * @return the enrollment reference, or `null` if the account has no active credential for
     * this method.
     */
    fun activeEnrollment(accountId: Long, method: String): EnrollmentRef?

    /**
     * The account's active credential for [method] whose `details` blob satisfies [matchesCaller]
     * - use this instead of [activeEnrollment] for methods that can have multiple simultaneous
     * active instances (e.g. `"device"`), where more than one instance may exist and only one is
     * a valid match for the calling device.
     *
     * [matchesCaller] is supplied by the caller (typically `ToolDescriptor.matchesCaller` bound to
     * its own binding key), not this port itself: `account` stores every method's `details` as an
     * opaque blob and must stay generic over what "matches" means for a given method - it never
     * hardcodes a concrete tool's own detail-map key (e.g. `"deviceBindingKeyRef"`, private to
     * `auth_device`).
     *
     * @return the enrollment reference of the first active instance [matchesCaller] accepts, or `null`.
     */
    fun activeInstanceEnrollment(accountId: Long, method: String, matchesCaller: (Map<String, Any?>?) -> Boolean): EnrollmentRef?
}
