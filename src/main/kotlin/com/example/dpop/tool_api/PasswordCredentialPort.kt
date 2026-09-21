package com.example.dpop.tool_api

import com.example.dpop.tool_spi.EnrollmentRef

/**
 * Verify/replace the password credential behind an [EnrollmentRef], for callers that already know
 * the account id directly with no Channel/ToolSession involved - e.g. Keycloak's native password
 * login/change (`OrchestratorPasswordStorageProvider`),
 * delegating to the same store the `auth-password`/`enroll-password` tool controllers use.
 */
interface PasswordCredentialPort {
    /**
     * @param enrollmentRef the account's active password enrollment, or `null` if it has none.
     * Implementations must run the same constant-cost check regardless of whether this is `null` -
     * never skip the call when there is no enrollment to check against.
     */
    fun verify(enrollmentRef: EnrollmentRef?, candidate: String): Boolean

    /** Hashes and stores [password] as a brand-new enrollment, returning its reference. */
    fun setNew(password: String): EnrollmentRef

    companion object {
        /**
         * The method name the password credential is filed under, declared by its owner at the
         * port rather than left as a string every caller spells out for itself.
         *
         * A caller that has to resolve the enrollment first (`AccountDirectory.activeEnrollment`)
         * needs this name, and until there was a second such caller it could stay private to
         * `auth_password`. Now there is one (`auth_kobil`, whose alternative unlock is the
         * account password), and a copied literal would be a second place where the name lives.
         */
        const val METHOD = "password"
    }
}
