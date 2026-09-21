package com.example.dpop.tool_spi

/**
 * Nominal wrapper for a tool's public identifier ("auth-sms", "enroll-password", ...) - see
 * [ToolDescriptor.toolId]. Deliberately NOT the same type as a method name (e.g. "sms"): a toolId
 * names one concrete procedure ((method, role) pair, this interface's own doc), a method name
 * names the credential family several tools can share - a `value class` is what stops the two
 * from being silently interchangeable the way two `String` fields would be, at zero runtime cost.
 */
@JvmInline
value class ToolId(val value: String) {
    override fun toString(): String = value
}

/**
 * A tool's self-description. Implement this once per tool; the aggregation of every
 * implementation in the application context is the tool catalog - there is no separate, centrally
 * maintained list to keep in sync.
 */
interface ToolDescriptor {
    /** The tool's public, stable identifier, e.g. `"auth-sms"`. Never derived from other fields. */
    val toolId: ToolId

    /**
     * The credential family this tool belongs to, e.g. `"sms"`. Shared by several
     * [ToolDescriptor]s that play different [MethodRole]s for the same underlying credential - an
     * SMS enrollment tool, its device-auth tool, and its lookup-auth tool all use `method = "sms"`.
     */
    val method: String

    /** The role this tool plays for [method]. See [MethodRole]. Its [MethodRole.category] is the tool's coarse grouping. */
    val role: MethodRole

    /**
     * The step name a freshly activated tool session starts on. Must match the `nextStep` this
     * tool's own first `ToolOutcome.InProgress` actually returns. Defaults to [role]'s own
     * [MethodRole.defaultStartStep]; override only if this tool's first step is genuinely named
     * differently.
     */
    val startStep: String get() = role.defaultStartStep

    /** The factor kinds this tool can provide at most. */
    val factorTypes: Set<FactorType>

    /** The highest level this tool can achieve, e.g. [AcrLevel.LOA2]. */
    val maxAcr: AcrLevel

    /**
     * The identifying attributes a successful run of this tool asserts about its subject, each
     * with the [ClaimSource] it is asserted with - carried as [Claim]s on
     * [ToolOutcome.Completed.Identified]/[ToolOutcome.Completed.Enrolled]. The declared
     * direction of the claims vocabulary, mirroring [factorTypes]: this set names what the
     * tool MAY assert on whose authority - the offer, answerable without any run having
     * happened (requires gates, catalog/matching planning); one concrete run's claims name
     * what it actually asserted, checked against this declaration by [assertClaimsCovered].
     */
    val claims: Set<ClaimDeclaration>
        get() = emptySet()

    /**
     * What the account must already have for this tool to be offered at all: each
     * [ClaimRequirement] is an [AttributeType] established at no less than its
     * [TrustLevel], checked against the account's consolidated value including
     * retractions (ADR-12). The mirror direction of [claims] - colloquially, "confirmed
     * email" is `ClaimRequirement(EMAIL, PROVEN)`. OFFERING gate only - it does not say the
     * tool consumes the value at run time (that is an anchor read), nor that a channel/journey
     * policy wants it (that is REGISTER's `emailObligation`, which keeps its own declarant).
     */
    val requires: Set<ClaimRequirement>
        get() = emptySet()

    /**
     * True if several active instances of this tool's method can coexist on one account at once
     * (e.g. one device credential per physical device), instead of the default rule where a new
     * enrollment replaces the previous active one.
     */
    val allowsMultipleInstances: Boolean
        get() = false

    /**
     * Non-`null` if this method's credential lives on ONE physical caller key and structurally
     * cannot exist anywhere else (a non-extractable device key) - so it is only ever usable from
     * the caller it was enrolled under, and becomes worthless the moment that key is bound to
     * another account (docs/09-dpop.md).
     *
     * A separate question from [allowsMultipleInstances], even though `device` happens to answer
     * both today. That one governs a storage rule ("does a new enrollment replace the previous
     * active one, or coexist with it"); this one governs offering and revocation ("may this
     * credential be offered here at all, and must it be revoked when the key moves"). Reading the
     * first as if it meant the second holds only while `device` is the one multi-instance method:
     * a future method that merely allows several parallel instances without being tied to a key
     * would silently inherit device semantics it never asked for.
     *
     * Deliberately a [CallerKeyBinding] rather than a `Boolean` beside an overridable predicate:
     * key-boundness is only meaningful together with the rule that tells the instances apart, so
     * declaring the one IS supplying the other. A flag could be set without the rule (every
     * instance would then count as living on every key alike - offering would stop filtering and a
     * rebind would revoke indiscriminately), or the rule written without the flag (never
     * consulted, since callers ask the flag first). Neither is expressible here.
     */
    val keyBinding: CallerKeyBinding?
        get() = null

    /**
     * The full "is this credential usable by this caller right now" check every caller needs.
     * A method that is not [keyBinding]-bound has nothing to restrict, so it is simply usable;
     * a key-bound one must satisfy both halves: the physical key must match (descriptor-specific,
     * only the owning module can say how) AND the account-ownership invariant every key-bound
     * method shares alike - a device can only ever be actively bound to ONE account at a time
     * ([com.example.dpop.orchestrator.session.DeviceAccountLink]), so a credential only counts as
     * usable while that link still names the SAME account it belongs to (docs/09-dpop.md).
     *
     * The second half is never descriptor-specific, so it lives here once rather than in every
     * [CallerKeyBinding].
     */
    fun usableByCaller(instanceDetails: Map<String, Any?>?, callerBindingKeyRef: String?, linkedAccountId: Long?, accountId: Long): Boolean {
        val binding = keyBinding ?: return true
        return binding.livesOn(instanceDetails, callerBindingKeyRef) && linkedAccountId == accountId
    }
}

/**
 * How a tool tells its own credential instances apart by the physical caller key each one lives
 * on - see [ToolDescriptor.keyBinding].
 *
 * Generic infrastructure that iterates key-bound credentials uniformly
 * (`orchestrator.journey.CandidateTools`, `orchestrator.policy.DefaultAuthPolicy`,
 * `orchestrator.journey.JourneyActionExecutor`) asks this to pick "the" instance on the key in
 * hand, without knowing HOW a concrete tool tells them apart - only the tool itself knows that
 * (`auth_device`'s own `"deviceBindingKeyRef"` detail key is private to that module, never
 * referenced outside it). tool_spi stays generic over every method exactly as
 * docs/03-tool-architektur.md #1 already requires for the rest of [ToolDescriptor]: "kein toolId
 * ist hier je ausgeschrieben" applies just as much to a concrete detail-map key.
 */
fun interface CallerKeyBinding {
    /**
     * Does the one active instance described by [instanceDetails] live on [callerBindingKeyRef]?
     *
     * [instanceDetails] is the blob the owning module itself wrote about THAT instance when it was
     * enrolled - opaque to everyone else, which is exactly why this question cannot be answered
     * anywhere but here. An instance with no details at all lives on no particular key.
     */
    fun livesOn(instanceDetails: Map<String, Any?>?, callerBindingKeyRef: String?): Boolean
}

/** Coarse grouping of a tool. See [MethodRole.category]. */
enum class ToolCategory {
    /** Establishes WHO the subject is, raising the identity axis (IAL) - never a credential. */
    IDENT,

    /** Creates a durable credential the account can authenticate with later. */
    ENROLL,

    /** Proves an existing credential, raising the authenticator axis (AAL). */
    AUTH,

    /**
     * A tool that decides on another channel's pending request instead of proving or establishing
     * anything about its own channel/account - never contributes to the own channel's ACR/AMR
     * balance, never a candidate for closing a gap (docs/03-tool-architektur.md).
     */
    SIDE_ACTION,

    /**
     * A tool that proves the subject CONTROLS an attribute (a code arrives there) without
     * resolving who they are and without creating a credential - e.g. confirming an email address
     * the account itself owns (`AttributeAuthority.LOCAL_ANCHOR`).
     *
     * Deliberately NOT [IDENT]: three places discriminate by category, and under [IDENT] this
     * would be offered as an identification procedure (`CandidateTools.forIdentification`,
     * `DefaultAuthPolicy.reIdentCandidates`) and, worse, its evidence would land on the IDENTITY
     * axis and raise the IAL - the first of ADR-5's three caps. Confirming an address must never
     * count as proof of identity. Like [SIDE_ACTION] it contributes nothing to its own channel's
     * ACR/AMR balance.
     */
    ATTEST
}

/**
 * The role a tool plays with respect to its [ToolDescriptor.method]. `(method, role)` together
 * uniquely identify a concrete procedure - `(method, category)` alone does not, since
 * [MethodRole.IDENTIFIED_AUTH] and [MethodRole.LOOKUP_AUTH] share `category=AUTH`.
 */
enum class MethodRole(val category: ToolCategory, val defaultStartStep: String) {
    /** Resolves identity (e.g. `ident-fsc`) - never establishes a durable credential. */
    IDENTIFICATION(ToolCategory.IDENT, "input"),

    /**
     * Attaches an ALREADY attested identity to the register person it belongs to (e.g.
     * `ident-kvnr` taking the Versichertennummer, docs/12-entscheidungen.md ADR-18). Same
     * category as [IDENTIFICATION] - it is part of establishing who someone is and contributes
     * to IAL - but a distinct role for the same reason [LOOKUP_AUTH] is distinct from
     * [IDENTIFIED_AUTH]: the category alone matches both, and these two must never be
     * interchangeable.
     *
     * What sets it apart is that it proves NOTHING by itself: whoever runs it supplies a mere
     * identifier, no secret and no possession. Declaring that here, rather than inferring it from
     * an empty [FactorType] set, keeps "correlates, never proves" a stated fact instead of an
     * incidental one.
     *
     * Three different things keep that from becoming a way in, and it is worth knowing which
     * carries what:
     * - It is never offered as a way to identify: `CandidateTools.forIdentification` and
     *   `DefaultAuthPolicy.reIdentCandidates` both filter on the ROLE, since the category alone
     *   would match this too.
     * - [ToolDescriptor.requires] gates the offer on attested attributes being present - but
     *   against the ACCOUNT's persisted claims, which may date from an earlier session. It
     *   guarantees there is something to match against, NOT that an attestation just happened.
     * - `JourneyActionExecutor.performRecordIdentification` refuses the act outright once the
     *   account has a person bound (attaching a second one is a change of identity, and no typed
     *   number could make it legitimate), and otherwise requires - unconditionally - that the
     *   register person matches what the account had attested, through
     *   `IdentityResolver.attestedIdentityMatches`. Both live with the act rather than with the
     *   strategy that offers it, so a future strategy cannot reopen either.
     *
     * It also contributes NO assurance: `ToolDescriptor.evidenceAxis()` answers `null` for this
     * role, so a completed run records no evidence and cannot move an ACR however it reports
     * itself. That matters because [ToolDescriptor.maxAcr] may well read `loa2` here - the number
     * describes the attestation the step rests on, not what typing an identifier proved.
     */
    CORRELATION(ToolCategory.IDENT, "input"),

    /** Creates a new credential for the method (e.g. `enroll-sms`). */
    ENROLLMENT(ToolCategory.ENROLL, "enroll"),

    /**
     * Proves a credential for an account already known via the current channel/process (e.g.
     * `auth-sms`, `auth-device`).
     */
    IDENTIFIED_AUTH(ToolCategory.AUTH, "auth"),

    /**
     * Proves the same underlying credential as its [IDENTIFIED_AUTH] sibling, but resolves the
     * account itself from a submitted identifier (e.g. `auth-sms-lookup`) instead of relying on
     * the channel already knowing it.
     */
    LOOKUP_AUTH(ToolCategory.AUTH, "auth"),

    /**
     * Approves or declines a pending request that originated on a DIFFERENT channel (e.g.
     * `confirm-qr-login` deciding a `auth-qr`/`auth-qr-lookup` pairing,
     * docs/03-tool-architektur.md) - structurally unlike every other role, which answers
     * "who am I"/"what can I prove" for its OWN channel.
     */
    PEER_APPROVAL(ToolCategory.SIDE_ACTION, "input"),

    /**
     * Attests an attribute the ACCOUNT owns (e.g. `confirm-email`): asserts claims, resolves no
     * identity and leaves no credential behind. The counterpart of [IDENTIFICATION] for a value
     * the subject controls rather than one the register vouches for - see [ToolCategory.ATTEST].
     */
    ATTESTATION(ToolCategory.ATTEST, "input")
}

/** A kind of authentication factor a method can provide. */
enum class FactorType {
    /** Something the user knows, e.g. a password. */
    KNOWLEDGE,
    /** Something the user has, e.g. a phone or a device key. */
    POSSESSION,
    /** Something the user is, e.g. biometrics. */
    INHERENCE
}
