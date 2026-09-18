package com.example.dpop.tool_api

import com.example.dpop.tool_spi.EnrollmentRef

/**
 * Creates an sms enrollment for callers that already know the account id directly, with no
 * Channel/ToolSession involved - e.g. the demo bootstrap seeding (`demo_seed`), which has to
 * establish a real method without running a TAN exchange.
 *
 * The sibling of [PasswordCredentialPort], and for the same reason: a caller outside the tool
 * machinery must be able to reach the credential store without depending on the `auth_sms`
 * module itself.
 */
interface SmsCredentialPort {
    /**
     * Stores [phoneNumber] as a brand-new, confirmed enrollment and returns its reference.
     *
     * There is no TAN check here - the caller stands in for one. That is only ever legitimate
     * for bootstrap seeding, never for a user-facing flow, where the enrollment must not exist
     * before a TAN was actually verified.
     */
    fun enroll(phoneNumber: String): EnrollmentRef
}
