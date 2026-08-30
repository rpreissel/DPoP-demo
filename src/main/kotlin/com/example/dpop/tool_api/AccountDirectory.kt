package com.example.dpop.tool_api

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
