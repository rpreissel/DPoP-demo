package com.example.dpop.orchestrator.policy

import com.example.dpop.account.AccountProfile
import com.example.dpop.account.AuthMethodView
import com.example.dpop.orchestrator.tool.ToolHandlerRegistry
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.MethodFamily
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.ToolDescriptor
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

/**
 * Pure unit tests against a small synthetic catalog (ident-fsc/enroll-sms/auth-sms plus a
 * hypothetical passkey pair) so the MFA-from-one-tool and capping rules from
 * docs/04-orchestrierung.md #2 are exercised even though the real catalog only has one
 * factor type today.
 */
class DefaultAuthPolicyTest : BehaviorSpec({

    fun descriptor(id: String, role: MethodRole, method: String, factorTypes: Set<FactorType>, maxAcr: String): ToolDescriptor =
        object : ToolDescriptor {
            override val toolId = id
            override val role = role
            override val methodFamily = MethodFamily(method)
            override val factorTypes = factorTypes
            override val maxAcr = maxAcr
        }

    val identFsc = descriptor("ident-fsc", MethodRole.IDENTIFICATION, "fsc", setOf(FactorType.POSSESSION), "loa2")
    val enrollSms = descriptor("enroll-sms", MethodRole.ENROLLMENT, "sms", setOf(FactorType.POSSESSION), "loa2")
    val authSms = descriptor("auth-sms", MethodRole.DEVICE_AUTH, "sms", setOf(FactorType.POSSESSION), "loa2")
    val authPasskey = descriptor("auth-passkey", MethodRole.DEVICE_AUTH, "passkey", setOf(FactorType.POSSESSION, FactorType.INHERENCE), "loa3")
    val enrollPasskey = descriptor("enroll-passkey", MethodRole.ENROLLMENT, "passkey", setOf(FactorType.POSSESSION, FactorType.INHERENCE), "loa3")

    val registry = ToolHandlerRegistry(listOf(identFsc, enrollSms, authSms, authPasskey, enrollPasskey))
    val policy = DefaultAuthPolicy(registry)

    fun account(vararg methods: AuthMethodView) = AccountProfile(
        accountId = 1L, personId = 1L, identifications = emptyList(), authenticationMethods = methods.toList()
    )

    fun method(method: String, enrolledUnderAcr: String, active: Boolean = true) =
        AuthMethodView(id = "$method-instance", method = method, active = active, createdAt = null, enrolledUnderAcr = enrolledUnderAcr, details = null)

    given("a synthetic catalog of ident-fsc/enroll-sms/auth-sms plus a hypothetical passkey pair") {

        `when`("evidence proves only sms - a single possession factor, below loa3") {
            val evidence = AuthEvidence(amr = listOf("sms"), factorTypes = setOf(FactorType.POSSESSION))

            then("isSatisfied only requires the level, not MFA") {
                policy.isSatisfied(evidence, "loa2", account = null) shouldBe true
                policy.isSatisfied(evidence, "loa1", account = null) shouldBe true
            }
        }

        `when`("checking isSatisfied at loa3") {
            then("a single factor type is not enough") {
                val singleFactor = AuthEvidence(amr = listOf("sms"), factorTypes = setOf(FactorType.POSSESSION))
                policy.isSatisfied(singleFactor, "loa3", account = null) shouldBe false
            }

            then("two distinct factor types proven by one tool are enough") {
                val twoFactors = AuthEvidence(amr = listOf("passkey"), factorTypes = setOf(FactorType.POSSESSION, FactorType.INHERENCE))
                policy.isSatisfied(twoFactors, "loa3", account = null) shouldBe true
            }
        }

        `when`("evidence carries two proofs of the same factor type") {
            then("MFA at loa3 is never satisfied") {
                val evidence = AuthEvidence(amr = listOf("sms", "someOtherPossessionMethod"), factorTypes = setOf(FactorType.POSSESSION))
                policy.isSatisfied(evidence, "loa3", account = null) shouldBe false
            }
        }

        `when`("an account has sms enrolled under loa1") {
            val acc = account(method("sms", enrolledUnderAcr = "loa1"))

            then("canAccountReach respects enrolledUnderAcr, not just the tool's maxAcr") {
                policy.canAccountReach(acc, "loa2") shouldBe false
                policy.canAccountReach(acc, "loa1") shouldBe true
            }
        }

        `when`("an account has no active method") {
            then("canAccountReach is false") {
                policy.canAccountReach(account(), "loa1") shouldBe false
            }
        }

        `when`("checking canAccountReach at MFA level (loa3)") {
            then("a single possession-only method is not enough") {
                val onlyPossession = account(method("sms", "loa2"))
                policy.canAccountReach(onlyPossession, "loa3") shouldBe false
            }

            then("a passkey covering two factor types on its own is enough") {
                val withPasskey = account(method("passkey", "loa3"))
                policy.canAccountReach(withPasskey, "loa3") shouldBe true
            }
        }

        `when`("resolving enrollment candidates") {
            then("already active methods are excluded") {
                val noMethods = account()
                policy.enrollmentCandidates(noMethods, "loa2") shouldContainExactlyInAnyOrder listOf("enroll-sms", "enroll-passkey")

                val withSms = account(method("sms", "loa2"))
                policy.enrollmentCandidates(withSms, "loa2") shouldContainExactly listOf("enroll-passkey")
            }
        }

        `when`("resolving candidate AUTH tools for the current session") {
            val acc = account(method("sms", "loa2"))

            then("methods already used this session are excluded") {
                val fresh = AuthEvidence(emptyList(), emptySet())
                policy.candidateTools(fresh, "loa2", acc, "test-binding-key") shouldContainExactly listOf("auth-sms")

                val alreadyUsedSms = AuthEvidence(listOf("sms"), setOf(FactorType.POSSESSION))
                policy.candidateTools(alreadyUsedSms, "loa2", acc, "test-binding-key").shouldBeEmpty()
            }
        }

        `when`("resolving the achieved ACR from proven amr methods") {
            then("it reflects the highest maxAcr among them") {
                policy.resolveAcr(AuthEvidence(emptyList(), emptySet()), account = null) shouldBe "none"
                policy.resolveAcr(AuthEvidence(listOf("sms"), setOf(FactorType.POSSESSION)), account = null) shouldBe "loa2"
                policy.resolveAcr(AuthEvidence(listOf("passkey"), setOf(FactorType.POSSESSION, FactorType.INHERENCE)), account = null) shouldBe "loa3"
            }
        }
    }

    given("two loa1-only tools of different factor types (a=possession, b=knowledge), distinct from the shared catalog") {
        val tokenA = descriptor("auth-a", MethodRole.DEVICE_AUTH, "a", setOf(FactorType.POSSESSION), "loa1")
        val tokenB = descriptor("auth-b", MethodRole.DEVICE_AUTH, "b", setOf(FactorType.KNOWLEDGE), "loa1")
        val localPolicy = DefaultAuthPolicy(ToolHandlerRegistry(listOf(tokenA, tokenB)))
        val evidence = AuthEvidence(amr = listOf("a", "b"), factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE))

        `when`("both were enrolled in a loa1-only session") {
            val bothWeak = account(method("a", enrolledUnderAcr = "loa1"), method("b", enrolledUnderAcr = "loa1"))

            // Nothing vouches for loa2, so the combination stays at loa1 (docs/06-ablaeufe.md #1
            // escalation concern, extended to combinations: a compromised weak session must not
            // be able to self-escalate by adding a 2nd weak factor).
            then("the MFA bump is capped at loa1") {
                localPolicy.resolveAcr(evidence, bothWeak) shouldBe "loa1"
                localPolicy.isSatisfied(evidence, "loa2", bothWeak) shouldBe false
                localPolicy.canAccountReach(bothWeak, "loa2") shouldBe false
            }
        }

        `when`("one of the two was enrolled right after a loa2-level session (e.g. an identification)") {
            val oneVouched = account(method("a", enrolledUnderAcr = "loa2"), method("b", enrolledUnderAcr = "loa1"))

            then("the pair reaches loa2 together") {
                localPolicy.resolveAcr(evidence, oneVouched) shouldBe "loa2"
                localPolicy.isSatisfied(evidence, "loa2", oneVouched) shouldBe true
                localPolicy.canAccountReach(oneVouched, "loa2") shouldBe true
            }
        }

        `when`("no account is given to check enrolledUnderAcr against") {
            then("the bump is conservatively withheld") {
                localPolicy.resolveAcr(evidence, account = null) shouldBe "loa1"
            }
        }
    }
})
