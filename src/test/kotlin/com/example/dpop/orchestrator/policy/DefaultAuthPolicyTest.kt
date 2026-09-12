package com.example.dpop.orchestrator.policy

import com.example.dpop.account.AccountProfile
import com.example.dpop.account.AuthMethodView
import com.example.dpop.orchestrator.tool.ToolHandlerRegistry
import com.example.dpop.tool_spi.FactorType
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
            override val method = method
            override val factorTypes = factorTypes
            override val maxAcr = maxAcr
        }

    val identFsc = descriptor("ident-fsc", MethodRole.IDENTIFICATION, "fsc", setOf(FactorType.POSSESSION), "loa2")
    val enrollSms = descriptor("enroll-sms", MethodRole.ENROLLMENT, "sms", setOf(FactorType.POSSESSION), "loa2")
    val authSms = descriptor("auth-sms", MethodRole.IDENTIFIED_AUTH, "sms", setOf(FactorType.POSSESSION), "loa2")
    val authPasskey = descriptor("auth-passkey", MethodRole.IDENTIFIED_AUTH, "passkey", setOf(FactorType.POSSESSION, FactorType.INHERENCE), "loa3")
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
            val evidence = AuthEvidence.from(amr = listOf("sms"), factorTypes = setOf(FactorType.POSSESSION), methodLoa = mapOf("sms" to "loa2"))

            then("isSatisfied only requires the level, not MFA") {
                policy.isSatisfied(evidence, "loa2", account = null) shouldBe true
                policy.isSatisfied(evidence, "loa1", account = null) shouldBe true
            }
        }

        `when`("checking isSatisfied at loa3") {
            then("a single factor type is not enough") {
                val singleFactor = AuthEvidence.from(amr = listOf("sms"), factorTypes = setOf(FactorType.POSSESSION), methodLoa = mapOf("sms" to "loa2"))
                policy.isSatisfied(singleFactor, "loa3", account = null) shouldBe false
            }

            then("two distinct factor types proven by one tool are enough") {
                val twoFactors = AuthEvidence.from(amr = listOf("passkey"), factorTypes = setOf(FactorType.POSSESSION, FactorType.INHERENCE), methodLoa = mapOf("passkey" to "loa3"))
                policy.isSatisfied(twoFactors, "loa3", account = null) shouldBe true
            }
        }

        `when`("evidence carries two proofs of the same factor type") {
            then("MFA at loa3 is never satisfied") {
                val evidence = AuthEvidence.from(
                    amr = listOf("sms", "someOtherPossessionMethod"),
                    factorTypes = setOf(FactorType.POSSESSION),
                    methodLoa = mapOf("sms" to "loa2", "someOtherPossessionMethod" to "loa2")
                )
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
                val fresh = AuthEvidence(emptyList())
                policy.candidateTools(fresh, "loa2", acc, "test-binding-key", linkedAccountId = acc.accountId) shouldContainExactly listOf("auth-sms")

                val alreadyUsedSms = AuthEvidence.from(listOf("sms"), setOf(FactorType.POSSESSION), mapOf("sms" to "loa2"))
                policy.candidateTools(alreadyUsedSms, "loa2", acc, "test-binding-key", linkedAccountId = acc.accountId).shouldBeEmpty()
            }

            then("a null bindingKeyRef (WEB channel, no device) is accepted without crashing") {
                val fresh = AuthEvidence(emptyList())
                policy.candidateTools(fresh, "loa2", acc, null, linkedAccountId = null) shouldContainExactly listOf("auth-sms")
            }
        }

        `when`("resolving candidate AUTH tools for a multi-instance (device-bound) method") {
            val deviceAuth = object : ToolDescriptor {
                override val toolId = "auth-device"
                override val role = MethodRole.IDENTIFIED_AUTH
                override val method = "device"
                override val factorTypes = setOf(FactorType.POSSESSION)
                override val maxAcr = "loa2"
                override val allowsMultipleInstances = true
                override fun matchesCaller(details: Map<String, Any?>?, callerBindingKeyRef: String?): Boolean =
                    details?.get("deviceBindingKeyRef") == callerBindingKeyRef
            }
            val deviceRegistry = ToolHandlerRegistry(listOf(deviceAuth))
            val devicePolicy = DefaultAuthPolicy(deviceRegistry)
            val deviceMethod = AuthMethodView(
                id = "device-instance", method = "device", active = true, createdAt = null,
                enrolledUnderAcr = "loa2", details = mapOf("deviceBindingKeyRef" to "key-1")
            )
            val acc = account(deviceMethod)
            val fresh = AuthEvidence(emptyList())

            then("it is offered while the device is still linked to this same account") {
                devicePolicy.candidateTools(fresh, "loa2", acc, "key-1", linkedAccountId = acc.accountId) shouldContainExactly listOf("auth-device")
            }

            then("it is NOT offered once the device has been rebound to a different account") {
                devicePolicy.candidateTools(fresh, "loa2", acc, "key-1", linkedAccountId = 999L).shouldBeEmpty()
            }
        }

        `when`("resolving re-identification candidates (reIdentCandidates)") {
            then("an IDENT tool already used this session is excluded, regardless of level") {
                val fresh = AuthEvidence(emptyList())
                policy.reIdentCandidates(fresh, "loa2") shouldContainExactly listOf("ident-fsc")

                val alreadyIdentified = AuthEvidence.from(listOf("fsc"), setOf(FactorType.POSSESSION))
                policy.reIdentCandidates(alreadyIdentified, "loa2").shouldBeEmpty()
            }

            then("an IDENT tool whose own maxAcr falls short of requiredAcr is excluded") {
                val fresh = AuthEvidence(emptyList())
                // ident-fsc tops out at loa2 (see catalog above) - can't close a loa3 gap on its own.
                policy.reIdentCandidates(fresh, "loa3").shouldBeEmpty()
            }

            then("AUTH/ENROLL tools never appear, only IDENTIFICATION-role ones") {
                policy.reIdentCandidates(AuthEvidence(emptyList()), "loa2") shouldContainExactly listOf("ident-fsc")
            }
        }

        `when`("explaining why an account can't reach a level (unreachableReason)") {
            then("no active method at all gives that as the reason") {
                policy.unreachableReason(account(), "loa2") shouldBe "Für dieses Konto ist derzeit kein aktives Anmeldeverfahren eingerichtet."
            }

            then("active methods sharing one factor type name that as the blocker") {
                // sms is the only active method here, so this is the "offered.size <= 1" branch,
                // not the "combinable but capped" one - see the loa1-cap case below for that.
                val onlySms = account(method("sms", "loa2"))
                policy.unreachableReason(onlySms, "loa3") shouldBe "Die aktiven Verfahren (sms) decken nur einen Faktor-Typ ab (Besitz). Für dieses Sicherheitsniveau ist zusätzlich ein Verfahren mit einem ANDEREN Faktor-Typ nötig, z. B. ein Passwort (Wissen), wenn bisher nur Besitz-Verfahren wie SMS oder E-Mail aktiv sind."
            }

            then("two combinable methods enrolled only under a lower level name the enrolledUnderAcr cap as the blocker") {
                val tokenA = descriptor("auth-a", MethodRole.IDENTIFIED_AUTH, "a", setOf(FactorType.POSSESSION), "loa1")
                val tokenB = descriptor("auth-b", MethodRole.IDENTIFIED_AUTH, "b", setOf(FactorType.KNOWLEDGE), "loa1")
                val localPolicy = DefaultAuthPolicy(ToolHandlerRegistry(listOf(tokenA, tokenB)))
                val bothWeak = account(method("a", enrolledUnderAcr = "loa1"), method("b", enrolledUnderAcr = "loa1"))

                localPolicy.unreachableReason(bothWeak, "loa2") shouldBe
                    "Die aktiven Verfahren würden in Kombination reichen, wurden aber unter einem niedrigeren " +
                    "Sicherheitsniveau eingerichtet (loa1) - das begrenzt, wie hoch sie gemeinsam wirken können. " +
                    "Ein neues Verfahren muss erst unter dem höheren Niveau eingerichtet werden."
            }
        }

        `when`("resolving the achieved ACR from proven amr methods") {
            then("it reflects the highest maxAcr among them") {
                policy.resolveAcr(AuthEvidence(emptyList()), account = null) shouldBe "none"
                policy.resolveAcr(AuthEvidence.from(listOf("sms"), setOf(FactorType.POSSESSION), mapOf("sms" to "loa2")), account = null) shouldBe "loa2"
                policy.resolveAcr(AuthEvidence.from(listOf("passkey"), setOf(FactorType.POSSESSION, FactorType.INHERENCE), mapOf("passkey" to "loa3")), account = null) shouldBe "loa3"
            }

            then("a single IDENTITY tool covering two factor types on its own (e.g. ident-eid: card+PIN) satisfies MFA on the IDENTITY axis alone") {
                val identEid = descriptor("ident-eid", MethodRole.IDENTIFICATION, "eid", setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE), "loa3")
                val localPolicy = DefaultAuthPolicy(ToolHandlerRegistry(listOf(identEid)))
                val evidence = AuthEvidence.from(
                    listOf("eid"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE), mapOf("eid" to "loa3"),
                    axis = mapOf("eid" to EvidenceAxis.IDENTITY)
                )
                localPolicy.resolveAcr(evidence, account = null) shouldBe "loa3"
                localPolicy.isSatisfied(evidence, "loa3", account = null) shouldBe true
            }

            then("a single ident-fsc reaches loa2 on its own IAL, not via an MFA bump") {
                val identOnly = AuthEvidence.from(
                    listOf("fsc"), setOf(FactorType.POSSESSION), mapOf("fsc" to "loa2"),
                    axis = mapOf("fsc" to EvidenceAxis.IDENTITY)
                )
                policy.resolveAcr(identOnly, account = null) shouldBe "loa2"
            }

            then("an identification must NOT combine with one unrelated AUTH factor into a false MFA bump") {
                // ident-fsc (POSSESSION, IDENTITY axis, loa2) plus a single KNOWLEDGE auth factor
                // (e.g. password) enrolled under a generously-claimed loa3 must NOT be treated as
                // if two AUTHENTICATOR factor types were proven together at loa3 - only "password
                // alone" is what an attacker who steals the password actually has to defeat, and
                // one method alone can never trigger the >=2-distinct-methods bump. Before the
                // IAL/AAL split, this combination would have wrongly resolved to "loa3" (fsc's own
                // loa2 base, bumped one tier via the fsc+password pairing, capped only by
                // password's claimed enrolledUnderAcr of loa3).
                val tokenPassword = descriptor("auth-password", MethodRole.IDENTIFIED_AUTH, "password", setOf(FactorType.KNOWLEDGE), "loa1")
                val localPolicy = DefaultAuthPolicy(ToolHandlerRegistry(listOf(identFsc, tokenPassword)))
                val evidence = AuthEvidence.from(
                    amr = listOf("fsc", "password"),
                    factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE),
                    methodLoa = mapOf("fsc" to "loa2", "password" to "loa1"),
                    enrolledUnderAcr = mapOf("password" to "loa3"),
                    axis = mapOf("fsc" to EvidenceAxis.IDENTITY, "password" to EvidenceAxis.AUTHENTICATOR)
                )
                localPolicy.resolveAcr(evidence, account = null) shouldBe "loa2"
                localPolicy.isSatisfied(evidence, "loa3", account = null) shouldBe false
            }
        }
    }

    given("two loa1-only tools of different factor types (a=possession, b=knowledge), distinct from the shared catalog") {
        val tokenA = descriptor("auth-a", MethodRole.IDENTIFIED_AUTH, "a", setOf(FactorType.POSSESSION), "loa1")
        val tokenB = descriptor("auth-b", MethodRole.IDENTIFIED_AUTH, "b", setOf(FactorType.KNOWLEDGE), "loa1")
        val localPolicy = DefaultAuthPolicy(ToolHandlerRegistry(listOf(tokenA, tokenB)))
        // enrolledUnderAcr is entirely the caller's own claim now (AuthEvidence.enrolledUnderAcr,
        // never re-derived from the account inside AuthPolicy) - each scenario below builds its
        // own evidence matching what it wants that claim to say, same as a real caller (JourneyService)
        // would resolve it from the account's own enrollment record before calling resolveAcr.
        fun evidence(enrolledUnderAcr: Map<String, String>) =
            AuthEvidence.from(amr = listOf("a", "b"), factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE), methodLoa = mapOf("a" to "loa1", "b" to "loa1"), enrolledUnderAcr = enrolledUnderAcr)

        `when`("both were enrolled in a loa1-only session") {
            val bothWeak = account(method("a", enrolledUnderAcr = "loa1"), method("b", enrolledUnderAcr = "loa1"))
            val weakEvidence = evidence(mapOf("a" to "loa1", "b" to "loa1"))

            // Nothing vouches for loa2, so the combination stays at loa1 (docs/06-ablaeufe.md #1
            // escalation concern, extended to combinations: a compromised weak session must not
            // be able to self-escalate by adding a 2nd weak factor).
            then("the MFA bump is capped at loa1") {
                localPolicy.resolveAcr(weakEvidence, bothWeak) shouldBe "loa1"
                localPolicy.isSatisfied(weakEvidence, "loa2", bothWeak) shouldBe false
                localPolicy.canAccountReach(bothWeak, "loa2") shouldBe false
            }
        }

        `when`("one of the two was enrolled right after a loa2-level session (e.g. an identification)") {
            val oneVouched = account(method("a", enrolledUnderAcr = "loa2"), method("b", enrolledUnderAcr = "loa1"))
            val vouchedEvidence = evidence(mapOf("a" to "loa2", "b" to "loa1"))

            then("the pair reaches loa2 together") {
                localPolicy.resolveAcr(vouchedEvidence, oneVouched) shouldBe "loa2"
                localPolicy.isSatisfied(vouchedEvidence, "loa2", oneVouched) shouldBe true
                localPolicy.canAccountReach(oneVouched, "loa2") shouldBe true
            }
        }

        `when`("no enrolledUnderAcr claim is given at all") {
            val evidence = evidence(emptyMap())

            then("the bump is conservatively withheld") {
                localPolicy.resolveAcr(evidence, account = null) shouldBe "loa1"
            }
        }
    }

    given("loa2 as this project's label for NIST 800-63B AAL2 (docs/04-orchestrierung.md #8)") {
        // AAL2 per NIST: "a multi-factor authenticator, OR a combination of two single-factor
        // authenticators" - both are already loa2 today; this given-block pins that contract down
        // explicitly so it survives future refactors as an intentional invariant, not an accident.

        `when`("two single-factor AUTH tools of different kinds combine, with no identification at all") {
            val tokenSms = descriptor("auth-sms", MethodRole.IDENTIFIED_AUTH, "sms", setOf(FactorType.POSSESSION), "loa1")
            val tokenPassword = descriptor("auth-password", MethodRole.IDENTIFIED_AUTH, "password", setOf(FactorType.KNOWLEDGE), "loa1")
            val localPolicy = DefaultAuthPolicy(ToolHandlerRegistry(listOf(tokenSms, tokenPassword)))
            val evidence = AuthEvidence.from(
                amr = listOf("sms", "password"),
                factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE),
                methodLoa = mapOf("sms" to "loa1", "password" to "loa1"),
                enrolledUnderAcr = mapOf("sms" to "loa2", "password" to "loa2")
            )

            then("NIST's 'two single-factor authenticators' path reaches loa2 (AAL2) on its own") {
                localPolicy.resolveAcr(evidence, account = null) shouldBe "loa2"
                localPolicy.isSatisfied(evidence, "loa2", account = null) shouldBe true
            }
        }

        `when`("a single AUTH tool declares two factor types itself (device-like: possession+knowledge)") {
            val tokenDevice = descriptor("auth-device", MethodRole.IDENTIFIED_AUTH, "device", setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE), "loa2")
            val localPolicy = DefaultAuthPolicy(ToolHandlerRegistry(listOf(tokenDevice)))
            val evidence = AuthEvidence.from(listOf("device"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE), mapOf("device" to "loa2"))

            then("NIST's 'multi-factor authenticator' path reaches loa2 (AAL2) alone, no combination needed") {
                localPolicy.resolveAcr(evidence, account = null) shouldBe "loa2"
                localPolicy.isSatisfied(evidence, "loa2", account = null) shouldBe true
            }
        }

        `when`("only an identification tool ran this session, no AUTH factor at all") {
            val evidence = AuthEvidence.from(
                listOf("fsc"), setOf(FactorType.POSSESSION), mapOf("fsc" to "loa2"),
                axis = mapOf("fsc" to EvidenceAxis.IDENTITY)
            )

            then("identification is an equally valid, not a lesser, path to the loa2/AAL2 threshold") {
                policy.resolveAcr(evidence, account = null) shouldBe "loa2"
                policy.isSatisfied(evidence, "loa2", account = null) shouldBe true
            }
        }

        `when`("two ALREADY loa2-rated methods of different factor types combine") {
            // NIST only defines a combination rule for reaching AAL2 (two single-factor
            // authenticators) - there is no NIST rule that combining two already-strong methods
            // reaches AAL3, which specifically requires a particular authenticator technology
            // (hardware-based, verifier-impersonation-resistant). The generic bump must therefore
            // never produce more than loa2, even when nothing else would stop it from reaching loa3.
            val tokenA = descriptor("auth-a", MethodRole.IDENTIFIED_AUTH, "a", setOf(FactorType.POSSESSION), "loa2")
            val tokenB = descriptor("auth-b", MethodRole.IDENTIFIED_AUTH, "b", setOf(FactorType.KNOWLEDGE), "loa2")
            val localPolicy = DefaultAuthPolicy(ToolHandlerRegistry(listOf(tokenA, tokenB)))
            val evidence = AuthEvidence.from(
                amr = listOf("a", "b"),
                factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE),
                methodLoa = mapOf("a" to "loa2", "b" to "loa2"),
                // Deliberately claims loa3 enrolledUnderAcr on both - even if nothing else would
                // cap the bump, the NIST ceiling must still hold it at loa2.
                enrolledUnderAcr = mapOf("a" to "loa3", "b" to "loa3")
            )

            then("the result stays at loa2, never bumps on to loa3") {
                localPolicy.resolveAcr(evidence, account = null) shouldBe "loa2"
            }
        }
    }
})
