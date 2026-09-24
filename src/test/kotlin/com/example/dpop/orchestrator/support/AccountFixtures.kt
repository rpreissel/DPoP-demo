package com.example.dpop.orchestrator.support

import com.example.dpop.account.AccountService
import com.example.dpop.auth_device.internal.DeviceEnrollment
import com.example.dpop.auth_device.internal.DeviceEnrollmentRepository
import com.example.dpop.auth_sms.internal.AuthSmsEnrollment
import com.example.dpop.auth_sms.internal.AuthSmsEnrollmentRepository
import com.example.dpop.orchestrator.session.SessionManagementService
import com.example.dpop.tool_api.EMAIL_ANCHOR_ENROLLMENT
import com.example.dpop.tool_api.PasswordCredentialPort
import com.example.dpop.tool_api.PersonDirectory
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.Claim
import com.example.dpop.tool_spi.ClaimSource
import com.example.dpop.tool_spi.EnrollmentRef
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Builds the account state an integration test needs as a PRECONDITION, through the same domain
 * services the real flow goes through - instead of replaying the registration click path over
 * HTTP.
 *
 * Why this exists: the journey's step order (ident -> confirm-email -> enroll-*) is the subject
 * of exactly two suites (RegistrationFlowIntegrationTest, RequiredActionIntegrationTest). Every
 * other suite only needs the RESULT - "an account with sms and password exists, bound to this
 * device". Spelling that result out as a click path made every reordering of the journey a
 * 15-file change.
 *
 * Deliberately NOT raw SQL: going through [AccountService] keeps the invariants (anchor floors,
 * singleton replacement, claim provenance) that the production path enforces, so a fixture can
 * not build an account state the flow itself could never produce. Same approach and reasoning as
 * `demo_seed.KcDemoAccountSeeder`, which seeds the demo accounts this way in production code.
 *
 * The values mirror a measured real run rather than a guess - notably `ENROLLED_UNDER_ACR`:
 * enrollments during registration are paid for with the identification's own loa2 evidence, not
 * with the enrolling tool's loa1 ceiling.
 */
@Component
class AccountFixtures(
    private val accountService: AccountService,
    private val personDirectory: PersonDirectory,
    private val passwordCredentialPort: PasswordCredentialPort,
    private val smsEnrollmentRepository: AuthSmsEnrollmentRepository,
    private val deviceEnrollmentRepository: DeviceEnrollmentRepository,
    private val sessionManagementService: SessionManagementService
) {

    /** A login method an account can be seeded with, in the shape the enrollment tools leave behind. */
    sealed interface Method {
        /** The `sms` method plus its auth_sms enrollment row. */
        data class Sms(val phoneNumber: String = PHONE_NUMBER) : Method

        /** The `password` method plus its auth_password enrollment row (hashed via the real port). */
        data class Password(val password: String = DEMO_PASSWORD) : Method

        /**
         * The `email` LOGIN method only (ADR-17) - the confirmed address itself is an anchor and
         * is seeded independently, by [seedAccount]'s `email` parameter.
         */
        data object Email : Method

        /**
         * The `device` method plus its auth_device enrollment row. [thumbprint] must be the one
         * the test's own DPoP key presents, otherwise the descriptor's `keyBinding` will not
         * offer it.
         */
        data class Device(val thumbprint: String, val label: String? = null) : Method
    }

    /**
     * Seeds one account and returns its id.
     *
     * @param email the confirmed address (an EMAIL anchor, as `confirm-email` would leave it), or
     * `null` for an account that never confirmed one.
     * @param bindDeviceKeyRef binds the account to this device (the orchestrator's
     * DeviceAccountLink), so a fresh channel on the same key is recognised. `null` leaves the
     * account unbound.
     */
    @Transactional
    fun seedAccount(
        kvnr: String = KVNR,
        name: String = NAME,
        vorname: String = VORNAME,
        email: String? = EMAIL,
        methods: List<Method> = emptyList(),
        bindDeviceKeyRef: String? = null,
        /**
         * The eID card anchor this person carries (ADR-19), as the `keycloak` demo seed writes it
         * for its own accounts - `null` for an account that has never been attested by a card.
         */
        restrictedId: String? = null
    ): Long {
        val accountId = accountService.createUnidentifiedAccount().accountId
        identify(accountId, kvnr, name, vorname)
        if (email != null) confirmEmail(accountId, email)
        if (restrictedId != null) {
            accountService.recordClaims(
                accountId,
                listOf(Claim(AttributeType.EID_RESTRICTED_ID, restrictedId, ClaimSource.DEMO_BOOTSTRAP, IDENT_ACR)),
                provenAcr = IDENT_ACR
            )
        }
        methods.forEach { addMethod(accountId, it) }
        if (bindDeviceKeyRef != null) sessionManagementService.linkDeviceToAccount(bindDeviceKeyRef, accountId)
        return accountId
    }

    /** What a completed ident-fsc run leaves behind: the four stammdaten claims plus the audit row. */
    private fun identify(accountId: Long, kvnr: String, name: String, vorname: String) {
        val personId = requireNotNull(personDirectory.findPersonIdByKvnr(kvnr)) {
            "No test person for kvnr $kvnr - see demo_seed/V16__testdata.sql"
        }
        accountService.recordClaims(
            accountId,
            listOf(
                Claim(AttributeType.PERSON_ID, personId.toString(), ClaimSource.PERSON_DIRECTORY, IDENT_ACR),
                Claim(AttributeType.KVNR, kvnr, ClaimSource.PERSON_DIRECTORY, IDENT_ACR),
                Claim(AttributeType.NAME, name, ClaimSource.PERSON_DIRECTORY, IDENT_ACR),
                Claim(AttributeType.VORNAME, vorname, ClaimSource.PERSON_DIRECTORY, IDENT_ACR)
            ),
            provenAcr = IDENT_ACR
        )
        accountService.addIdentification(accountId, "fsc", IDENT_ACR.value, emptyMap())
    }

    /** What confirm-email leaves behind: an EMAIL anchor and NO login method (ADR-17). */
    private fun confirmEmail(accountId: Long, email: String) {
        accountService.recordClaims(
            accountId,
            listOf(Claim(AttributeType.EMAIL, email, CONFIRM_EMAIL_SOURCE, AcrLevel.LOA1)),
            provenAcr = ENROLLED_UNDER_ACR
        )
    }

    private fun addMethod(accountId: Long, method: Method) {
        val instanceId = UUID.randomUUID()
        when (method) {
            is Method.Sms -> {
                val enrollment = smsEnrollmentRepository.save(AuthSmsEnrollment(phoneNumber = method.phoneNumber))
                accountService.recordClaims(
                    accountId,
                    listOf(Claim(AttributeType.PHONE_NUMBER, method.phoneNumber, ENROLL_SMS_SOURCE, AcrLevel.LOA1)),
                    provenAcr = ENROLLED_UNDER_ACR,
                    authMethodId = instanceId
                )
                accountService.addAuthenticationMethod(
                    accountId, "sms",
                    EnrollmentRef("auth_sms.enrollment", enrollment.id.toString()),
                    enrolledUnderAcr = ENROLLED_UNDER_ACR.value,
                    details = mapOf("smsProvider" to "sms-gw", "enrolledUnderAmr" to listOf("fsc")),
                    instanceId = instanceId
                )
            }

            is Method.Password -> accountService.addAuthenticationMethod(
                accountId, "password",
                passwordCredentialPort.setNew(method.password),
                enrolledUnderAcr = ENROLLED_UNDER_ACR.value,
                details = mapOf("enrolledUnderAmr" to listOf("fsc")),
                instanceId = instanceId
            )

            is Method.Email -> accountService.addAuthenticationMethod(
                accountId, "email", EMAIL_ANCHOR_ENROLLMENT,
                enrolledUnderAcr = ENROLLED_UNDER_ACR.value,
                details = mapOf("enrolledUnderAmr" to listOf("fsc")),
                instanceId = instanceId
            )

            is Method.Device -> {
                val enrollment = deviceEnrollmentRepository.save(DeviceEnrollment(thumbprint = method.thumbprint))
                accountService.addAuthenticationMethod(
                    accountId, "device",
                    EnrollmentRef("auth_device.enrollment", enrollment.id.toString()),
                    enrolledUnderAcr = ENROLLED_UNDER_ACR.value,
                    details = mapOf(
                        "thumbprint" to method.thumbprint,
                        "deviceBindingKeyRef" to method.thumbprint,
                        "enrolledUnderAmr" to listOf("fsc")
                    ),
                    allowsMultipleInstances = true,
                    label = method.label,
                    instanceId = instanceId
                )
            }
        }
    }

    companion object {
        const val KVNR = "A123456789"
        const val NAME = "Muster"
        const val VORNAME = "Max"
        const val EMAIL = "max.mustermann@example.com"
        const val PHONE_NUMBER = "+491701234567"
        const val DEMO_PASSWORD = "correct-horse-battery"

        /** ident-fsc's own achieved level - what the stammdaten claims are proven with. */
        private val IDENT_ACR = AcrLevel.LOA2

        /**
         * What enrollments during a registration are actually paid for. Measured, not assumed:
         * the orchestrator uses the evidence established so far (the identification's loa2), not
         * the enrolling tool's own loa1 ceiling.
         */
        private val ENROLLED_UNDER_ACR = AcrLevel.LOA2

        private val CONFIRM_EMAIL_SOURCE = ClaimSource("confirm-email")
        private val ENROLL_SMS_SOURCE = ClaimSource("enroll-sms")
    }
}
