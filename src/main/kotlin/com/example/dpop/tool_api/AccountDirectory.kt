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
     * Resolves the account that has established [value] as its anchor of [type] - the generic
     * resolve-identity read of the claims model. Lookup runs on the attribute type's normalized
     * form, exactly like the write side does. Typed lookups such as [resolveAccountByEmail]
     * are extensions delegating here. [type] must be a local anchor attribute
     * (`PERSON_ID` or `EMAIL`). KVNR is resolved live through PersonDirectory to a PersonId,
     * then through the PERSON_ID anchor; it is not a local account anchor.
     *
     * @return the account id, or `null` if no account has established this anchor.
     */
    fun resolveByAnchor(type: AttributeType, value: String): Long?

    /**
     * The account's established value for anchor [type], in the attribute type's normalized
     * form - the reverse read of [resolveByAnchor]: what that one resolves BY, per account.
     * `null` if this account has never established the anchor. A rejected change preserves
     * the previous value. Non-anchor types, including KVNR, are contract errors.
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
     * The account's active credential for [method] whose own instance details satisfy
     * [livesOnCallerKey] - use this instead of [activeEnrollment] for methods that can have
     * multiple simultaneous active instances (e.g. `"device"`), where more than one instance may
     * exist and only one belongs to the calling device.
     *
     * [livesOnCallerKey] is supplied by the caller (typically `ToolDescriptor.keyBinding` bound to
     * its own binding key), not this port itself: `account` stores every instance's details as an
     * opaque blob and must stay generic over what "lives on this key" means for a given method -
     * it never hardcodes a concrete tool's own detail-map key (e.g. `"deviceBindingKeyRef"`,
     * private to `auth_device`).
     *
     * @return the enrollment reference of the first active instance [livesOnCallerKey] accepts, or `null`.
     */
    fun activeInstanceEnrollment(accountId: Long, method: String, livesOnCallerKey: (instanceDetails: Map<String, Any?>?) -> Boolean): EnrollmentRef?
}

/**
 * The credential of the `email` method is not a module-owned row but the account's own EMAIL
 * anchor (`account.anchor`, keyed by account and attribute type) - every enrollment of that
 * method references it the same way, and no `EnrollmentCleanup` exists for it: the anchor goes
 * with the account.
 */
val EMAIL_ANCHOR_ENROLLMENT = EnrollmentRef(type = "account.anchor", id = "email")
