package com.example.dpop.orchestrator.policy

import com.example.dpop.account.AccountProfile
import com.example.dpop.account.AuthMethodView
import com.example.dpop.orchestrator.tool.ToolHandlerRegistry
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.ToolCategory
import com.example.dpop.tool_spi.ToolDescriptor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure unit tests against a small synthetic catalog (ident-fsc/enroll-sms/auth-sms plus a
 * hypothetical passkey pair) so the MFA-from-one-tool and capping rules from
 * docs/04-orchestrierung.md #2 are exercised even though the real catalog only has one
 * factor type today.
 */
class DefaultAuthPolicyTest {

    private fun descriptor(id: String, category: ToolCategory, method: String, factorTypes: Set<FactorType>, maxAcr: String): ToolDescriptor =
        object : ToolDescriptor {
            override val toolId = id
            override val category = category
            override val method = method
            override val factorTypes = factorTypes
            override val maxAcr = maxAcr
        }

    private val identFsc = descriptor("ident-fsc", ToolCategory.IDENT, "fsc", setOf(FactorType.POSSESSION), "loa2")
    private val enrollSms = descriptor("enroll-sms", ToolCategory.ENROLL, "sms", setOf(FactorType.POSSESSION), "loa2")
    private val authSms = descriptor("auth-sms", ToolCategory.AUTH, "sms", setOf(FactorType.POSSESSION), "loa2")
    private val authPasskey = descriptor("auth-passkey", ToolCategory.AUTH, "passkey", setOf(FactorType.POSSESSION, FactorType.INHERENCE), "loa3")
    private val enrollPasskey = descriptor("enroll-passkey", ToolCategory.ENROLL, "passkey", setOf(FactorType.POSSESSION, FactorType.INHERENCE), "loa3")

    private val registry = ToolHandlerRegistry(listOf(identFsc, enrollSms, authSms, authPasskey, enrollPasskey))
    private val policy = DefaultAuthPolicy(registry)

    private fun account(vararg methods: AuthMethodView) = AccountProfile(
        accountId = 1L, personId = 1L, identifications = emptyList(), authenticationMethods = methods.toList()
    )

    private fun method(method: String, enrolledUnderAcr: String, active: Boolean = true) =
        AuthMethodView(method, active, null, enrolledUnderAcr, null)

    @Test
    fun isSatisfied_requiresOnlyLevel_belowLoa3() {
        val evidence = AuthEvidence(amr = listOf("sms"), factorTypes = setOf(FactorType.POSSESSION))
        assertThat(policy.isSatisfied(evidence, "loa2")).isTrue()
        assertThat(policy.isSatisfied(evidence, "loa1")).isTrue()
    }

    @Test
    fun isSatisfied_atLoa3_requiresTwoDistinctFactorTypes() {
        val singleFactor = AuthEvidence(amr = listOf("sms"), factorTypes = setOf(FactorType.POSSESSION))
        assertThat(policy.isSatisfied(singleFactor, "loa3")).isFalse()

        val twoFactors = AuthEvidence(amr = listOf("passkey"), factorTypes = setOf(FactorType.POSSESSION, FactorType.INHERENCE))
        assertThat(policy.isSatisfied(twoFactors, "loa3")).isTrue()
    }

    @Test
    fun twoSameFactorProofs_neverSatisfyMfa() {
        val evidence = AuthEvidence(amr = listOf("sms", "someOtherPossessionMethod"), factorTypes = setOf(FactorType.POSSESSION))
        assertThat(policy.isSatisfied(evidence, "loa3")).isFalse()
    }

    @Test
    fun canAccountReach_respectsEnrolledUnderAcr_notJustTheToolsMaxAcr() {
        val acc = account(method("sms", enrolledUnderAcr = "loa1"))
        assertThat(policy.canAccountReach(acc, "loa2")).isFalse()
        assertThat(policy.canAccountReach(acc, "loa1")).isTrue()
    }

    @Test
    fun canAccountReach_isFalse_withoutAnyActiveMethod() {
        assertThat(policy.canAccountReach(account(), "loa1")).isFalse()
    }

    @Test
    fun canAccountReach_atMfaLevel_needsTwoDistinctFactorTypesAcrossMethods() {
        val onlyPossession = account(method("sms", "loa2"))
        assertThat(policy.canAccountReach(onlyPossession, "loa3")).isFalse()

        val withPasskey = account(method("passkey", "loa3"))
        assertThat(policy.canAccountReach(withPasskey, "loa3")).isTrue()
    }

    @Test
    fun enrollmentCandidates_excludeAlreadyActiveMethods() {
        val noMethods = account()
        assertThat(policy.enrollmentCandidates(noMethods, "loa2")).containsExactlyInAnyOrder("enroll-sms", "enroll-passkey")

        val withSms = account(method("sms", "loa2"))
        assertThat(policy.enrollmentCandidates(withSms, "loa2")).containsExactly("enroll-passkey")
    }

    @Test
    fun candidateTools_excludeMethodsAlreadyUsedThisSession() {
        val acc = account(method("sms", "loa2"))
        val fresh = AuthEvidence(emptyList(), emptySet())
        assertThat(policy.candidateTools(fresh, "loa2", acc)).containsExactly("auth-sms")

        val alreadyUsedSms = AuthEvidence(listOf("sms"), setOf(FactorType.POSSESSION))
        assertThat(policy.candidateTools(alreadyUsedSms, "loa2", acc)).isEmpty()
    }

    @Test
    fun resolveAcr_reflectsTheHighestMaxAcrAmongProvenAmrMethods() {
        assertThat(policy.resolveAcr(AuthEvidence(emptyList(), emptySet()))).isEqualTo("none")
        assertThat(policy.resolveAcr(AuthEvidence(listOf("sms"), setOf(FactorType.POSSESSION)))).isEqualTo("loa2")
        assertThat(policy.resolveAcr(AuthEvidence(listOf("passkey"), setOf(FactorType.POSSESSION, FactorType.INHERENCE)))).isEqualTo("loa3")
    }
}
