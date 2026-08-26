package com.example.dpop.tool_spi

/**
 * An opaque reference to a credential enrollment record owned by some module.
 *
 * @property type identifies which kind of enrollment this is (e.g. `"auth_sms_enrollment"`).
 * @property id the enrollment's id within its own store, as a string.
 */
data class EnrollmentRef(val type: String, val id: String)
