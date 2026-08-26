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
     * The account's active credential for [method] on the specific device identified by
     * [deviceBindingKeyRef]. Use this instead of [activeEnrollment] for methods that can have
     * multiple simultaneous active instances (e.g. `"device"`), where more than one instance may
     * exist and only the one bound to the requesting device is a valid match.
     *
     * @return the enrollment reference, or `null` if no matching credential exists for this device.
     */
    fun activeDeviceEnrollment(accountId: Long, method: String, deviceBindingKeyRef: String): EnrollmentRef?
}
