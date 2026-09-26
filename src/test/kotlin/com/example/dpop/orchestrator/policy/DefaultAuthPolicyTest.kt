package com.example.dpop.orchestrator.policy

import com.example.dpop.orchestrator.domain.policy.requiresSatisfied
import com.example.dpop.orchestrator.domain.policy.AuthEvidence
import com.example.dpop.orchestrator.domain.policy.AuthPolicy
import com.example.dpop.orchestrator.domain.policy.CandidateContext
import com.example.dpop.orchestrator.domain.policy.EvidenceAxis
import com.example.dpop.orchestrator.domain.policy.Reachability
import com.example.dpop.orchestrator.domain.policy.UnreachableReason
import com.example.dpop.tool_spi.ClaimSource
import com.example.dpop.tool_spi.ClaimDeclaration
import com.example.dpop.account.AccountProfile
import com.example.dpop.account.AuthMethodView
import com.example.dpop.orchestrator.tool.ToolHandlerRegistry
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.CallerKeyBinding
import com.example.dpop.tool_spi.ClaimRequirement
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ToolId
import com.example.dpop.tool_spi.TrustLevel
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

    fun descriptor(id: String, role: MethodRole, method: String, factorTypes: Set<FactorType>, maxAcr: AcrLevel): ToolDescriptor =
        object : ToolDescriptor {
            override val toolId = ToolId(id)
            override val role = role
            override val method = method
            override val factorTypes = factorTypes
            override val maxAcr = maxAcr
            // What the catalog demands of every identification (ToolHandlerRegistry, ADR-39).
            override val claims =
                if (role == MethodRole.IDENTIFICATION) setOf(AttributeType.FAMILY_NAME, AttributeType.GIVEN_NAMES, AttributeType.BIRTH_DATE)
                    .map { ClaimDeclaration(it, ClaimSource.of(toolId)) }.toSet()
                else emptySet()
        }

    val identFsc = descriptor("ident-fsc", MethodRole.IDENTIFICATION, "fsc", setOf(FactorType.POSSESSION), AcrLevel.LOA2)
    val enrollSms = descriptor("enroll-sms", MethodRole.ENROLLMENT, "sms", setOf(FactorType.POSSESSION), AcrLevel.LOA2)
    val authSms = descriptor("auth-sms", MethodRole.IDENTIFIED_AUTH, "sms", setOf(FactorType.POSSESSION), AcrLevel.LOA2)
    val authPasskey = descriptor("auth-passkey", MethodRole.IDENTIFIED_AUTH, "passkey", setOf(FactorType.POSSESSION, FactorType.INHERENCE), AcrLevel.LOA3)
    val enrollPasskey = descriptor("enroll-passkey", MethodRole.ENROLLMENT, "passkey", setOf(FactorType.POSSESSION, FactorType.INHERENCE), AcrLevel.LOA3)

    val registry = ToolHandlerRegistry(listOf(identFsc, enrollSms, authSms, authPasskey, enrollPasskey))
    val policy = DefaultAuthPolicy(registry)

    fun candidates(
        evidence: AuthEvidence,
        requiredAcr: AcrLevel,
        account: AccountProfile? = null,
        bindingKeyRef: String? = null,
        linkedAccountId: Long? = null,
        availableTools: Set<ToolId>? = null
    ) = CandidateContext(evidence, requiredAcr, account, bindingKeyRef, linkedAccountId, availableTools)


    fun account(vararg methods: AuthMethodView) = AccountProfile(
        accountId = 1L, personId = "P000000001", authenticationMethods = methods.toList()
    )

    fun method(method: String, enrolledUnderAcr: AcrLevel, active: Boolean = true) =
        AuthMethodView(id = "$method-instance", method = method, active = active, createdAt = null, enrolledUnderAcr = enrolledUnderAcr.value, details = null, enrollmentRef = EnrollmentRef("${method}_enrollment", "1"))

    given("a synthetic catalog of ident-fsc/enroll-sms/auth-sms plus a hypothetical passkey pair") {

        `when`("evidence proves only sms - a single possession factor, below loa3") {
            val evidence = AuthEvidence.from(amr = listOf("sms"), factorTypes = setOf(FactorType.POSSESSION), methodAcr = mapOf("sms" to AcrLevel.LOA2.value))

            then("isSatisfied only requires the level, not MFA") {
                policy.isSatisfied(evidence, AcrLevel.LOA2, account = null) shouldBe true
                policy.isSatisfied(evidence, AcrLevel.LOA1, account = null) shouldBe true
            }
        }

        `when`("checking isSatisfied at loa3") {
            then("a single factor type is not enough") {
                val singleFactor = AuthEvidence.from(amr = listOf("sms"), factorTypes = setOf(FactorType.POSSESSION), methodAcr = mapOf("sms" to AcrLevel.LOA2.value))
                policy.isSatisfied(singleFactor, AcrLevel.LOA3, account = null) shouldBe false
            }

            then("two distinct factor types proven by one tool are enough") {
                val twoFactors = AuthEvidence.from(amr = listOf("passkey"), factorTypes = setOf(FactorType.POSSESSION, FactorType.INHERENCE), methodAcr = mapOf("passkey" to AcrLevel.LOA3.value))
                policy.isSatisfied(twoFactors, AcrLevel.LOA3, account = null) shouldBe true
            }
        }

        `when`("evidence carries two proofs of the same factor type") {
            then("MFA at loa3 is never satisfied") {
                val evidence = AuthEvidence.from(
                    amr = listOf("sms", "someOtherPossessionMethod"),
                    factorTypes = setOf(FactorType.POSSESSION),
                    methodAcr = mapOf("sms" to AcrLevel.LOA2.value, "someOtherPossessionMethod" to AcrLevel.LOA2.value)
                )
                policy.isSatisfied(evidence, AcrLevel.LOA3, account = null) shouldBe false
            }
        }

        `when`("an account has sms enrolled under loa1") {
            val acc = account(method("sms", enrolledUnderAcr = AcrLevel.LOA1))

            then("reachability respects enrolledUnderAcr, not just the tool's maxAcr") {
                // Single method, single factor type: the "needs another factor type" branch is
                // checked first regardless of whether level or MFA was the actual blocker (see
                // DefaultAuthPolicy.reachability's own doc) - not the enrolledUnderAcr cap message.
                policy.reachability(acc, AcrLevel.LOA2) shouldBe
                    Reachability.NotReachable(UnreachableReason.SingleFactorType(listOf("sms"), setOf(FactorType.POSSESSION)))
                policy.reachability(acc, AcrLevel.LOA1) shouldBe Reachability.Reachable
            }
        }

        `when`("an account has no active method") {
            then("reachability is NotReachable(NoActiveMethod)") {
                policy.reachability(account(), AcrLevel.LOA1) shouldBe Reachability.NotReachable(UnreachableReason.NoActiveMethod)
            }
        }

        `when`("checking reachability at MFA level (loa3)") {
            then("a single possession-only method is not enough") {
                val onlyPossession = account(method("sms", AcrLevel.LOA2))
                policy.reachability(onlyPossession, AcrLevel.LOA3) shouldBe
                    Reachability.NotReachable(UnreachableReason.SingleFactorType(listOf("sms"), setOf(FactorType.POSSESSION)))
            }

            then("a passkey covering two factor types on its own is enough") {
                val withPasskey = account(method("passkey", AcrLevel.LOA3))
                policy.reachability(withPasskey, AcrLevel.LOA3) shouldBe Reachability.Reachable
            }
        }

        `when`("resolving enrollment candidates") {
            then("already active methods are excluded") {
                val noMethods = account()
                policy.enrollmentCandidates(candidates(AuthEvidence(emptyList()), AcrLevel.LOA2, noMethods)) shouldContainExactlyInAnyOrder listOf(ToolId("enroll-sms"), ToolId("enroll-passkey"))

                val withSms = account(method("sms", AcrLevel.LOA2))
                policy.enrollmentCandidates(candidates(AuthEvidence(emptyList()), AcrLevel.LOA2, withSms)) shouldContainExactly listOf(ToolId("enroll-passkey"))
            }
        }

        `when`("resolving candidate AUTH tools for the current session") {
            val acc = account(method("sms", AcrLevel.LOA2))

            then("methods already used this session are excluded") {
                val fresh = AuthEvidence(emptyList())
                policy.authCandidates(candidates(fresh, AcrLevel.LOA2, acc, "test-binding-key", linkedAccountId = acc.accountId, availableTools = null)) shouldContainExactly listOf(ToolId("auth-sms"))

                val alreadyUsedSms = AuthEvidence.from(listOf("sms"), setOf(FactorType.POSSESSION), mapOf("sms" to AcrLevel.LOA2.value))
                policy.authCandidates(candidates(alreadyUsedSms, AcrLevel.LOA2, acc, "test-binding-key", linkedAccountId = acc.accountId, availableTools = null)).shouldBeEmpty()
            }

            then("a null bindingKeyRef (WEB channel, no device) is accepted without crashing") {
                val fresh = AuthEvidence(emptyList())
                policy.authCandidates(candidates(fresh, AcrLevel.LOA2, acc, null, linkedAccountId = null, availableTools = null)) shouldContainExactly listOf(ToolId("auth-sms"))
            }
        }

        `when`("an active method alone reaches the target but this channel can never actually offer it (availableTools)") {
            then("it must not suppress the two-factor combination fallback for the methods that ARE offerable here - regression for a real bug report (CONFIRM_PEER_LOGIN aborting for an account with both sms and email active)") {
                // Mirrors the real catalog: auth-qr has maxAcr=loa2 on its own (no combination
                // needed), but the App frontend never declares it in availableTools (no UI for it,
                // docs/03-tool-architektur.md) - sms/email are each capped at loa1 individually.
                val tokenSms = descriptor("auth-sms", MethodRole.IDENTIFIED_AUTH, "sms", setOf(FactorType.POSSESSION), AcrLevel.LOA1)
                val tokenEmail = descriptor("auth-email", MethodRole.IDENTIFIED_AUTH, "email", setOf(FactorType.KNOWLEDGE), AcrLevel.LOA1)
                val tokenQr = descriptor("auth-qr", MethodRole.IDENTIFIED_AUTH, "qr", setOf(FactorType.POSSESSION), AcrLevel.LOA2)
                val localPolicy = DefaultAuthPolicy(ToolHandlerRegistry(listOf(tokenSms, tokenEmail, tokenQr)))
                val acc = account(method("sms", AcrLevel.LOA2), method("email", AcrLevel.LOA2), method("qr", AcrLevel.LOA2))
                val fresh = AuthEvidence(emptyList())

                // Before the fix: qr's own maxAcr=loa2 sets singleMethodSuffices=true (computed
                // over ALL active methods, unfiltered), which disables helpsMfa for sms/email too -
                // and since qr itself is excluded by availableTools, authCandidates came back
                // completely empty despite sms+email clearly combining to loa2.
                localPolicy.authCandidates(
                    candidates(
                        fresh, AcrLevel.LOA2, acc, "test-binding-key", linkedAccountId = acc.accountId,
                        availableTools = setOf(ToolId("auth-sms"), ToolId("auth-email"))
                    )
                ) shouldContainExactlyInAnyOrder listOf(ToolId("auth-sms"), ToolId("auth-email"))
            }
        }

        `when`("resolving candidate AUTH tools for a key-bound method") {
            val deviceAuth = object : ToolDescriptor {
                override val toolId = ToolId("auth-device")
                override val role = MethodRole.IDENTIFIED_AUTH
                override val method = "device"
                override val factorTypes = setOf(FactorType.POSSESSION)
                override val maxAcr = AcrLevel.LOA2
                override val allowsMultipleInstances = true
                override val keyBinding = CallerKeyBinding { instanceDetails, callerBindingKeyRef ->
                    instanceDetails?.get("deviceBindingKeyRef") == callerBindingKeyRef
                }
            }
            val deviceRegistry = ToolHandlerRegistry(listOf(deviceAuth))
            val devicePolicy = DefaultAuthPolicy(deviceRegistry)
            val deviceMethod = AuthMethodView(
                id = "device-instance", method = "device", active = true, createdAt = null,
                enrolledUnderAcr = AcrLevel.LOA2.value, details = mapOf("deviceBindingKeyRef" to "key-1"),
                enrollmentRef = EnrollmentRef("auth_device.enrollment", "1")
            )
            val acc = account(deviceMethod)
            val fresh = AuthEvidence(emptyList())

            then("it is offered while the device is still linked to this same account") {
                devicePolicy.authCandidates(candidates(fresh, AcrLevel.LOA2, acc, "key-1", linkedAccountId = acc.accountId, availableTools = null)) shouldContainExactly listOf(ToolId("auth-device"))
            }

            then("it is NOT offered once the device has been rebound to a different account") {
                devicePolicy.authCandidates(candidates(fresh, AcrLevel.LOA2, acc, "key-1", linkedAccountId = 999L, availableTools = null)).shouldBeEmpty()
            }
        }

        `when`("resolving re-identification candidates (reIdentCandidates)") {
            then("an IDENT tool already used this session is excluded, regardless of level") {
                val fresh = AuthEvidence(emptyList())
                policy.reIdentCandidates(candidates(fresh, AcrLevel.LOA2)) shouldContainExactly listOf(ToolId("ident-fsc"))

                val alreadyIdentified = AuthEvidence.from(listOf("fsc"), setOf(FactorType.POSSESSION))
                policy.reIdentCandidates(candidates(alreadyIdentified, AcrLevel.LOA2)).shouldBeEmpty()
            }

            then("an IDENT tool whose own maxAcr falls short of requiredAcr is excluded") {
                val fresh = AuthEvidence(emptyList())
                // ident-fsc tops out at loa2 (see catalog above) - can't close a loa3 gap on its own.
                policy.reIdentCandidates(candidates(fresh, AcrLevel.LOA3)).shouldBeEmpty()
            }

            then("AUTH/ENROLL tools never appear, only IDENTIFICATION-role ones") {
                policy.reIdentCandidates(candidates(AuthEvidence(emptyList()), AcrLevel.LOA2)) shouldContainExactly listOf(ToolId("ident-fsc"))
            }
        }

        `when`("explaining why an account can't reach a level (reachability's NotReachable reason)") {
            then("no active method at all gives that as the reason") {
                policy.reachability(account(), AcrLevel.LOA2) shouldBe Reachability.NotReachable(UnreachableReason.NoActiveMethod)
            }

            then("active methods sharing one factor type name that as the blocker") {
                // sms is the only active method here, so this is the "offered.size <= 1" branch,
                // not the "combinable but capped" one - see the loa1-cap case below for that.
                val onlySms = account(method("sms", AcrLevel.LOA2))
                policy.reachability(onlySms, AcrLevel.LOA3) shouldBe
                    Reachability.NotReachable(UnreachableReason.SingleFactorType(listOf("sms"), setOf(FactorType.POSSESSION)))
            }

            then("two combinable methods enrolled only under a lower level name the enrolledUnderAcr cap as the blocker") {
                val tokenA = descriptor("auth-a", MethodRole.IDENTIFIED_AUTH, "a", setOf(FactorType.POSSESSION), AcrLevel.LOA1)
                val tokenB = descriptor("auth-b", MethodRole.IDENTIFIED_AUTH, "b", setOf(FactorType.KNOWLEDGE), AcrLevel.LOA1)
                val localPolicy = DefaultAuthPolicy(ToolHandlerRegistry(listOf(tokenA, tokenB)))
                val bothWeak = account(method("a", enrolledUnderAcr = AcrLevel.LOA1), method("b", enrolledUnderAcr = AcrLevel.LOA1))

                localPolicy.reachability(bothWeak, AcrLevel.LOA2) shouldBe
                    Reachability.NotReachable(UnreachableReason.CombinationCapped(AcrLevel.LOA1))
            }

            then("a single method covering two factor types on its own names the enrolledUnderAcr cap, never 'needs another factor type'") {
                // Regression for a real bug report: `passkey` alone already covers POSSESSION and
                // INHERENCE, so `distinctMethods < 2` must never gate the "needs a different factor
                // type" message here - that would list this very method's own multiple factor types
                // right after claiming it covers only one (self-contradictory).
                val onlyPasskeyWeak = account(method("passkey", enrolledUnderAcr = AcrLevel.LOA1))
                val reachability = policy.reachability(onlyPasskeyWeak, AcrLevel.LOA3)

                reachability shouldBe Reachability.NotReachable(UnreachableReason.SingleMethodCapped("passkey", AcrLevel.LOA1))
            }
        }

        `when`("resolving the achieved ACR from proven amr methods") {
            then("it reflects the highest maxAcr among them") {
                policy.resolveAcr(AuthEvidence(emptyList()), account = null) shouldBe AcrLevel.NONE
                policy.resolveAcr(AuthEvidence.from(listOf("sms"), setOf(FactorType.POSSESSION), mapOf("sms" to AcrLevel.LOA2.value)), account = null) shouldBe AcrLevel.LOA2
                policy.resolveAcr(AuthEvidence.from(listOf("passkey"), setOf(FactorType.POSSESSION, FactorType.INHERENCE), mapOf("passkey" to AcrLevel.LOA3.value)), account = null) shouldBe AcrLevel.LOA3
            }

            then("a single IDENTITY tool covering two factor types on its own (e.g. ident-eid: card+PIN) satisfies MFA on the IDENTITY axis alone") {
                val identEid = descriptor("ident-eid", MethodRole.IDENTIFICATION, "eid", setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE), AcrLevel.LOA3)
                val localPolicy = DefaultAuthPolicy(ToolHandlerRegistry(listOf(identEid)))
                val evidence = AuthEvidence.from(
                    listOf("eid"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE), mapOf("eid" to AcrLevel.LOA3.value),
                    axis = mapOf("eid" to EvidenceAxis.IDENTITY)
                )
                localPolicy.resolveAcr(evidence, account = null) shouldBe AcrLevel.LOA3
                localPolicy.isSatisfied(evidence, AcrLevel.LOA3, account = null) shouldBe true
            }

            then("a single ident-fsc reaches loa2 on its own IAL, not via an MFA bump") {
                val identOnly = AuthEvidence.from(
                    listOf("fsc"), setOf(FactorType.POSSESSION), mapOf("fsc" to AcrLevel.LOA2.value),
                    axis = mapOf("fsc" to EvidenceAxis.IDENTITY)
                )
                policy.resolveAcr(identOnly, account = null) shouldBe AcrLevel.LOA2
            }

            then("an identification must NOT combine with one unrelated AUTH factor into a false MFA bump") {
                // ident-fsc (POSSESSION, IDENTITY axis, loa2) plus a single KNOWLEDGE auth factor
                // (e.g. password) enrolled under a generously-claimed loa3 must NOT be treated as
                // if two AUTHENTICATOR factor types were proven together at loa3 - only "password
                // alone" is what an attacker who steals the password actually has to defeat, and
                // one method alone can never trigger the >=2-distinct-methods bump. Before the
                // IAL/AAL split, this combination would have wrongly resolved to AcrLevel.LOA3.value (fsc's own
                // loa2 base, bumped one tier via the fsc+password pairing, capped only by
                // password's claimed enrolledUnderAcr of loa3).
                val tokenPassword = descriptor("auth-password", MethodRole.IDENTIFIED_AUTH, "password", setOf(FactorType.KNOWLEDGE), AcrLevel.LOA1)
                val localPolicy = DefaultAuthPolicy(ToolHandlerRegistry(listOf(identFsc, tokenPassword)))
                val evidence = AuthEvidence.from(
                    amr = listOf("fsc", "password"),
                    factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE),
                    methodAcr = mapOf("fsc" to AcrLevel.LOA2.value, "password" to AcrLevel.LOA1.value),
                    enrolledUnderAcr = mapOf("password" to AcrLevel.LOA3.value),
                    axis = mapOf("fsc" to EvidenceAxis.IDENTITY, "password" to EvidenceAxis.AUTHENTICATOR)
                )
                localPolicy.resolveAcr(evidence, account = null) shouldBe AcrLevel.LOA2
                localPolicy.isSatisfied(evidence, AcrLevel.LOA3, account = null) shouldBe false
            }
        }
    }

    given("two loa1-only tools of different factor types (a=possession, b=knowledge), distinct from the shared catalog") {
        val tokenA = descriptor("auth-a", MethodRole.IDENTIFIED_AUTH, "a", setOf(FactorType.POSSESSION), AcrLevel.LOA1)
        val tokenB = descriptor("auth-b", MethodRole.IDENTIFIED_AUTH, "b", setOf(FactorType.KNOWLEDGE), AcrLevel.LOA1)
        val localPolicy = DefaultAuthPolicy(ToolHandlerRegistry(listOf(tokenA, tokenB)))
        // enrolledUnderAcr is entirely the caller's own claim now (AuthEvidence.enrolledUnderAcr,
        // never re-derived from the account inside AuthPolicy) - each scenario below builds its
        // own evidence matching what it wants that claim to say, same as a real caller (JourneyService)
        // would resolve it from the account's own enrollment record before calling resolveAcr.
        fun evidence(enrolledUnderAcr: Map<String, String>) =
            AuthEvidence.from(amr = listOf("a", "b"), factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE), methodAcr = mapOf("a" to AcrLevel.LOA1.value, "b" to AcrLevel.LOA1.value), enrolledUnderAcr = enrolledUnderAcr)

        `when`("both were enrolled in a loa1-only session") {
            val bothWeak = account(method("a", enrolledUnderAcr = AcrLevel.LOA1), method("b", enrolledUnderAcr = AcrLevel.LOA1))
            val weakEvidence = evidence(mapOf("a" to AcrLevel.LOA1.value, "b" to AcrLevel.LOA1.value))

            // Nothing vouches for loa2, so the combination stays at loa1 (docs/06-ablaeufe.md #1
            // escalation concern, extended to combinations: a compromised weak session must not
            // be able to self-escalate by adding a 2nd weak factor).
            then("the MFA bump is capped at loa1") {
                localPolicy.resolveAcr(weakEvidence, bothWeak) shouldBe AcrLevel.LOA1
                localPolicy.isSatisfied(weakEvidence, AcrLevel.LOA2, bothWeak) shouldBe false
                localPolicy.reachability(bothWeak, AcrLevel.LOA2) shouldBe Reachability.NotReachable(UnreachableReason.CombinationCapped(AcrLevel.LOA1))
            }
        }

        `when`("one of the two was enrolled right after a loa2-level session (e.g. an identification)") {
            val oneVouched = account(method("a", enrolledUnderAcr = AcrLevel.LOA2), method("b", enrolledUnderAcr = AcrLevel.LOA1))
            val vouchedEvidence = evidence(mapOf("a" to AcrLevel.LOA2.value, "b" to AcrLevel.LOA1.value))

            then("the pair reaches loa2 together") {
                localPolicy.resolveAcr(vouchedEvidence, oneVouched) shouldBe AcrLevel.LOA2
                localPolicy.isSatisfied(vouchedEvidence, AcrLevel.LOA2, oneVouched) shouldBe true
                localPolicy.reachability(oneVouched, AcrLevel.LOA2) shouldBe Reachability.Reachable
            }
        }

        `when`("no enrolledUnderAcr claim is given at all") {
            val evidence = evidence(emptyMap())

            then("the bump is conservatively withheld") {
                localPolicy.resolveAcr(evidence, account = null) shouldBe AcrLevel.LOA1
            }
        }
    }

    given("loa2 as this project's label for NIST 800-63B AAL2 (docs/04-orchestrierung.md #8)") {
        // AAL2 per NIST: "a multi-factor authenticator, OR a combination of two single-factor
        // authenticators" - both are already loa2 today; this given-block pins that contract down
        // explicitly so it survives future refactors as an intentional invariant, not an accident.

        `when`("two single-factor AUTH tools of different kinds combine, with no identification at all") {
            val tokenSms = descriptor("auth-sms", MethodRole.IDENTIFIED_AUTH, "sms", setOf(FactorType.POSSESSION), AcrLevel.LOA1)
            val tokenPassword = descriptor("auth-password", MethodRole.IDENTIFIED_AUTH, "password", setOf(FactorType.KNOWLEDGE), AcrLevel.LOA1)
            val localPolicy = DefaultAuthPolicy(ToolHandlerRegistry(listOf(tokenSms, tokenPassword)))
            val evidence = AuthEvidence.from(
                amr = listOf("sms", "password"),
                factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE),
                methodAcr = mapOf("sms" to AcrLevel.LOA1.value, "password" to AcrLevel.LOA1.value),
                enrolledUnderAcr = mapOf("sms" to AcrLevel.LOA2.value, "password" to AcrLevel.LOA2.value)
            )

            then("NIST's 'two single-factor authenticators' path reaches loa2 (AAL2) on its own") {
                localPolicy.resolveAcr(evidence, account = null) shouldBe AcrLevel.LOA2
                localPolicy.isSatisfied(evidence, AcrLevel.LOA2, account = null) shouldBe true
            }
        }

        `when`("a single AUTH tool declares two factor types itself (device-like: possession+knowledge)") {
            val tokenDevice = descriptor("auth-device", MethodRole.IDENTIFIED_AUTH, "device", setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE), AcrLevel.LOA2)
            val localPolicy = DefaultAuthPolicy(ToolHandlerRegistry(listOf(tokenDevice)))
            val evidence = AuthEvidence.from(listOf("device"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE), mapOf("device" to AcrLevel.LOA2.value))

            then("NIST's 'multi-factor authenticator' path reaches loa2 (AAL2) alone, no combination needed") {
                localPolicy.resolveAcr(evidence, account = null) shouldBe AcrLevel.LOA2
                localPolicy.isSatisfied(evidence, AcrLevel.LOA2, account = null) shouldBe true
            }
        }

        `when`("only an identification tool ran this session, no AUTH factor at all") {
            val evidence = AuthEvidence.from(
                listOf("fsc"), setOf(FactorType.POSSESSION), mapOf("fsc" to AcrLevel.LOA2.value),
                axis = mapOf("fsc" to EvidenceAxis.IDENTITY)
            )

            then("identification is an equally valid, not a lesser, path to the loa2/AAL2 threshold") {
                policy.resolveAcr(evidence, account = null) shouldBe AcrLevel.LOA2
                policy.isSatisfied(evidence, AcrLevel.LOA2, account = null) shouldBe true
            }
        }

        `when`("two ALREADY loa2-rated methods of different factor types combine") {
            // NIST only defines a combination rule for reaching AAL2 (two single-factor
            // authenticators) - there is no NIST rule that combining two already-strong methods
            // reaches AAL3, which specifically requires a particular authenticator technology
            // (hardware-based, verifier-impersonation-resistant). The generic bump must therefore
            // never produce more than loa2, even when nothing else would stop it from reaching loa3.
            val tokenA = descriptor("auth-a", MethodRole.IDENTIFIED_AUTH, "a", setOf(FactorType.POSSESSION), AcrLevel.LOA2)
            val tokenB = descriptor("auth-b", MethodRole.IDENTIFIED_AUTH, "b", setOf(FactorType.KNOWLEDGE), AcrLevel.LOA2)
            val localPolicy = DefaultAuthPolicy(ToolHandlerRegistry(listOf(tokenA, tokenB)))
            val evidence = AuthEvidence.from(
                amr = listOf("a", "b"),
                factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE),
                methodAcr = mapOf("a" to AcrLevel.LOA2.value, "b" to AcrLevel.LOA2.value),
                // Deliberately claims loa3 enrolledUnderAcr on both - even if nothing else would
                // cap the bump, the NIST ceiling must still hold it at loa2.
                enrolledUnderAcr = mapOf("a" to AcrLevel.LOA3.value, "b" to AcrLevel.LOA3.value)
            )

            then("the result stays at loa2, never bumps on to loa3") {
                localPolicy.resolveAcr(evidence, account = null) shouldBe AcrLevel.LOA2
            }
        }
    }

    given("requiresSatisfied - the generic ToolDescriptor.requires gate") {
        fun profile(vararg established: Pair<AttributeType, TrustLevel>) = AccountProfile(
            accountId = 1L, personId = null, authenticationMethods = emptyList(),
            establishedClaims = established.toMap()
        )

        then("an attribute established at the required level satisfies it") {
            requiresSatisfied(
                ClaimRequirement(AttributeType.FAMILY_NAME, TrustLevel.PROVEN),
                profile(AttributeType.FAMILY_NAME to TrustLevel.PROVEN)
            ) shouldBe true
        }

        then("a stronger source satisfies a weaker requirement") {
            requiresSatisfied(
                ClaimRequirement(AttributeType.FAMILY_NAME, TrustLevel.PROVEN),
                profile(AttributeType.FAMILY_NAME to TrustLevel.AUTHORITATIVE)
            ) shouldBe true
        }

        then("a weaker source does not satisfy a stronger requirement") {
            requiresSatisfied(
                ClaimRequirement(AttributeType.FAMILY_NAME, TrustLevel.PROVEN),
                profile(AttributeType.FAMILY_NAME to TrustLevel.SELF_REPORTED)
            ) shouldBe false
        }

        then("an attribute the account never established does not satisfy it") {
            requiresSatisfied(
                ClaimRequirement(AttributeType.BIRTH_DATE, TrustLevel.PROVEN),
                profile(AttributeType.FAMILY_NAME to TrustLevel.PROVEN)
            ) shouldBe false
        }

        // A retraction removes the claim from establishedClaims (ADR-12, the `not exists`
        // subtraction in AccountClaimRepository.findEstablished) - so a withdrawn value stops
        // satisfying the gate, which an emailConfirmed-style flag could never express.
        then("a retracted attribute stops satisfying it") {
            requiresSatisfied(ClaimRequirement(AttributeType.EMAIL, TrustLevel.PROVEN), profile()) shouldBe false
        }

        then("no account at all satisfies nothing") {
            requiresSatisfied(ClaimRequirement(AttributeType.EMAIL, TrustLevel.PROVEN), null) shouldBe false
        }

        // enroll-password's own gate, unchanged in meaning - now one case of the general rule
        // rather than its definition.
        then("a confirmed email still satisfies ClaimRequirement(EMAIL, PROVEN)") {
            requiresSatisfied(
                ClaimRequirement(AttributeType.EMAIL, TrustLevel.PROVEN),
                profile(AttributeType.EMAIL to TrustLevel.PROVEN)
            ) shouldBe true
        }
    }
})
