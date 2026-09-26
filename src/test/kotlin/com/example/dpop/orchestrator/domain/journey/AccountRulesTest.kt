package com.example.dpop.orchestrator.domain.journey

import com.example.dpop.account.AccountProfile
import com.example.dpop.account.AuthMethodView
import com.example.dpop.orchestrator.domain.policy.AuthEvidence
import com.example.dpop.orchestrator.domain.policy.EvidenceAxis
import com.example.dpop.orchestrator.domain.policy.MethodEvidence
import com.example.dpop.orchestrator.domain.policy.MethodName
import com.example.dpop.tool_api.IdentityConflictException
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.ToolId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * The account rules of the acting phase, as tables - no Spring, no database
 * (docs/adr/ADR-040-fachkern-im-paket-domain.md). The integration tests run the same rules end to end.
 */
class AccountRulesTest : BehaviorSpec({

    fun method(id: String) = AuthMethodView(id, "sms", true, null, "loa1", null, EnrollmentRef("sms", id))
    fun account(id: Long, personId: String? = null, methods: List<AuthMethodView> = emptyList()) =
        AccountProfile(accountId = id, personId = personId, authenticationMethods = methods)

    val provisional = account(1)
    val interessent = account(2, methods = listOf(method("m2")))
    val identified = account(3, personId = "P3", methods = listOf(method("m3")))

    given("an identification that resolved to no existing account") {
        then("with nothing in hand it opens a new account") {
            IdentificationTarget.forUnresolved(null) { error("not asked") } shouldBe IdentificationTarget.NewAccount
        }
        then("an unidentified account in hand takes it - as the same person only") {
            IdentificationTarget.forUnresolved(interessent) { true } shouldBe IdentificationTarget.AccountInHand(2)
            shouldThrow<IdentityConflictException> { IdentificationTarget.forUnresolved(interessent) { false } }
        }
        then("an identified account in hand refuses it - that would be a second person") {
            shouldThrow<IdentityConflictException> { IdentificationTarget.forUnresolved(identified) { true } }
        }
    }

    given("two accounts meeting in one run (ADR-20)") {
        then("the provisional one in hand moves into the resolved one - also when both are provisional") {
            AccountMerge.decide(provisional) { identified } shouldBe AccountMerge.MoveInto(from = 1, into = 3)
            AccountMerge.decide(provisional) { account(4) } shouldBe AccountMerge.MoveInto(from = 1, into = 4)
        }
        then("a provisional resolved one is absorbed into the one in hand") {
            AccountMerge.decide(interessent) { account(5) } shouldBe AccountMerge.AbsorbResolved(resolved = 5, into = 2)
        }
        then("two real accounts are never merged by an identification") {
            shouldThrow<IdentityConflictException> { AccountMerge.decide(interessent) { identified } }
        }
    }

    given("a correlation step (ADR-18)") {
        then("it passes only for an unbound account and a matching person") {
            checkCorrelation(interessent, ToolId("ident-kvnr"), "P9") { it == "P9" }
            shouldThrow<IdentityConflictException> { checkCorrelation(interessent, ToolId("ident-kvnr"), "P9") { false } }
        }
        then("an account that already has a person is refused before anything is compared") {
            shouldThrow<IdentityConflictException> { checkCorrelation(identified, ToolId("ident-kvnr"), "P3") { true } }
        }
        then("a correlation that resolved nobody is a broken tool, not a case") {
            shouldThrow<IllegalStateException> { checkCorrelation(interessent, ToolId("ident-kvnr"), null) { true } }
        }
    }

    given("an attested address that belongs to another account") {
        fun evidence(vararg axes: EvidenceAxis) = AuthEvidence(axes.map {
            MethodEvidence(MethodName("m"), AcrLevel.LOA2, source = "ORCHESTRATOR", amrSourceId = "t", axis = it)
        })
        then("without an identification in this session it never moves the session") {
            shouldThrow<IdentityConflictException> { checkAttestationMove(evidence(EvidenceAxis.AUTHENTICATOR), null) { true } }
        }
        then("after one, it moves only to an account whose person the attested identity fits") {
            checkAttestationMove(evidence(EvidenceAxis.IDENTITY), null) { error("no person to compare") }
            checkAttestationMove(evidence(EvidenceAxis.IDENTITY), "P3") { true }
            shouldThrow<IdentityConflictException> { checkAttestationMove(evidence(EvidenceAxis.IDENTITY), "P3") { false } }
        }
    }

    given("a proven credential") {
        then("only a lookup tool may name the account, and only one agreeing with the account in hand") {
            accountOfProof(MethodRole.LOOKUP_AUTH, namedByTool = 7, inHand = null) shouldBe 7
            accountOfProof(MethodRole.LOOKUP_AUTH, namedByTool = 7, inHand = 7) shouldBe 7
            shouldThrow<IdentityConflictException> { accountOfProof(MethodRole.LOOKUP_AUTH, namedByTool = 7, inHand = 8) }
        }
        then("any other role proves the account in hand, whatever it names") {
            accountOfProof(MethodRole.IDENTIFIED_AUTH, namedByTool = 7, inHand = 8) shouldBe 8
        }
    }
})
