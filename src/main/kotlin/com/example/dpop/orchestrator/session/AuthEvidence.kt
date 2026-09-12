package com.example.dpop.orchestrator.session

import com.example.dpop.orchestrator.policy.AuthEvidence as CoreAuthEvidence
import com.example.dpop.orchestrator.policy.EvidenceAxis
import com.example.dpop.orchestrator.policy.MethodEvidence
import com.example.dpop.orchestrator.policy.MethodName
import com.example.dpop.tool_spi.FactorType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * The persistent, central evidence record - one per channel, cleared at logout (not per account:
 * a step-up channel gets its own fresh row, evidence continuity across flow runs is the kc-facade's
 * own `RestoreData` mechanism, not a shared account-wide row). Named the same as
 * [com.example.dpop.orchestrator.policy.AuthEvidence] deliberately - this IS that evidence,
 * persisted; import-alias the core type where both are needed in one file
 * (`import ... AuthEvidence as CoreAuthEvidence`). `currentAcr` is deliberately NOT a field here:
 * it is always `AuthPolicy.resolveAcr(coreEvidence, account)`, recomputed live by every reader -
 * storing it would only ever duplicate what `amrEvidence` already determines, and combining more
 * evidence can never lower the resolved level, so there is nothing a cached max() could add.
 */
@Entity
@Table(name = "auth_evidence")
class AuthEvidence(
    @Column(name = "account_id", nullable = false)
    var accountId: Long? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "auth_evidence_id", nullable = false)
    var authEvidenceId: UUID? = null

    /**
     * One record per method proven in THIS channel; does not survive it (docs/04-orchestrierung.md
     * #1). Deliberately ONE JSON column of records rather than several parallel Method->X columns
     * (the same reasoning as `AuthEvidence.MethodEvidence` in `orchestrator.policy`, which this
     * mirrors at the persistence layer): nothing then lets a method appear in one collection but
     * not another. [currentAmr], [currentAmrSource], [methodLoa], [enrolledUnderAcr],
     * [currentFactorTypes] below are derived views over this, kept only so every existing read
     * site can stay unchanged.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "amr_evidence", nullable = false)
    var amrEvidence: MutableList<AmrRecord> = mutableListOf()

    /** Every method proven so far - derived from [amrEvidence], never a separately stored collection to fall out of sync. */
    val currentAmr: List<String> get() = amrEvidence.map { it.method }

    /**
     * Who proved each entry in [currentAmr] - [AmrSource.ORCHESTRATOR] for a completed
     * orchestrator tool, [AmrSource.KEYCLOAK] for evidence a native Keycloak authenticator
     * already established (docs/05-api.md Abschnitt 3, `JourneyService.
     * applyEvidenceUpdate`). Exposed via `AuthData.amr` (KEYCLOAK channels only) so Keycloak's own
     * flow can tell which of its own steps already ran vs. which the orchestrator contributed -
     * the orchestrator alone still resolves the combined `acr`, this is purely informational.
     */
    val currentAmrSource: Map<String, String> get() = amrEvidence.associate { it.method to it.source }

    /**
     * Each entry in [currentAmr]'s own loa (docs/05-api.md Abschnitt 3) - the ONLY thing
     * `AuthPolicy.resolveAcr` prices from (`AuthEvidence.MethodEvidence.loa`), regardless of
     * whether the entry came from a completed orchestrator tool (its own achieved/capped level) or
     * a native Keycloak authenticator's self-reported one; neither this field nor the policy that
     * reads it needs to know which.
     */
    val methodLoa: Map<String, String> get() = amrEvidence.associate { it.method to it.loa }

    /**
     * Each entry in [currentAmr]'s own ceiling for an MFA combination involving it (docs/06-
     * ablaeufe.md #1, `AuthEvidence.MethodEvidence.enrolledUnderAcr`) - the caller that recorded
     * the entry always supplies this directly (an orchestrator method's own account enrollment
     * record; a natively reported method's own conservative claim, docs/05-api.md
     * Abschnitt 3), never derived here.
     */
    val enrolledUnderAcr: Map<String, String> get() = amrEvidence.mapNotNull { r -> r.enrolledUnderAcr?.let { r.method to it } }.toMap()

    /**
     * Each entry in [currentAmr]'s own contributed factor kinds - kept per-method (not derived
     * from the method name itself: amr values name procedures, not factor kinds,
     * docs/02-domaenenmodell.md #5), unioned here for callers that only need the channel-wide set.
     */
    val currentFactorTypes: Set<FactorType> get() = amrEvidence.flatMap { it.factorTypes }.toSet()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null

    @Version
    @Column(name = "version", nullable = false)
    var version: Long? = null

    init {
        updatedAt = Instant.now()
    }

    /**
     * Pure merge/upsert: adds or updates the given methods, never removes one. Used by
     * [JourneyService.applyOutcome][com.example.dpop.orchestrator.journey.JourneyService] for a
     * single completed orchestrator tool's own proof - a one-off event, never "here is my whole
     * current set", so a removal semantic makes no sense there. Each [update]'s own `source`
     * (`MethodEvidence.source`) wins for a method that has no recorded source yet, or that was
     * last reported as [AmrSource.KEYCLOAK] - Keycloak's own report is a "Selbstauskunft" the
     * orchestrator can't verify (docs/12-entscheidungen.md ADR-7), so an actual
     * orchestrator-tool proof for the SAME method is always the stronger claim and upgrades it.
     * The reverse never happens: once a method is [AmrSource.ORCHESTRATOR], a later `kc`-sourced
     * report for it is silently ignored (source-wise; the method itself is unaffected). Not a
     * single flat `source` parameter for the whole call - each entry decides for itself, so a
     * batch mixing sources (e.g. a step-up's `RestoreData` restoring an orchestrator-proven
     * method alongside freshly-reported kc ones) is handled correctly method by method. Takes the
     * REAL, core `MethodEvidence` type (one list, not several per-field maps keyed by method) - a
     * caller always has a complete record per method by the time it calls this, so there is
     * nothing left to reconstruct from `existing` except [factorTypes] (deliberately still
     * unioned: the total factor kinds ever demonstrated for this method within this evidence
     * trail, not just the latest report's own).
     */
    fun addAmr(updates: List<MethodEvidence>) {
        for (update in updates) {
            val method = update.method.value
            val existing = amrEvidence.find { it.method == method }
            val newSource = if (existing == null || existing.source == AmrSource.KEYCLOAK) update.source else existing.source
            val record = AmrRecord(
                method = method,
                source = newSource,
                loa = update.loa.value,
                enrolledUnderAcr = update.enrolledUnderAcr?.value,
                factorTypes = (existing?.factorTypes ?: emptySet()) + update.factorTypes,
                amrSourceId = update.amrSourceId,
                axis = update.axis,
            )
            amrEvidence = (amrEvidence.filterNot { it.method == method } + record).toMutableList()
        }
        updatedAt = Instant.now()
    }

    /**
     * Sync, not merge: [updates] is the CALLER's complete, currently-valid set for [source] - not
     * a delta (docs/05-api.md Abschnitt 3). Any existing [AmrRecord] whose `source`
     * is CURRENTLY [source] but whose method is missing from [updates] is dropped (it expired -
     * this is what makes a native method's possible time-based lifetime representable at all:
     * Keycloak recomputes its own still-valid AMR set, similar to its `AmrUtils`, and this mirrors
     * that recomputation instead of only ever accumulating). A record already upgraded to a
     * DIFFERENT source is untouched even if its method is missing from [updates] - removal only
     * ever targets records CURRENTLY owned by [source], so the "kc never downgrades orchestrator"
     * invariant on [addAmr] holds here for free, without a separate check. Every method actually
     * IN [updates] is then upserted exactly like [addAmr].
     */
    fun replaceForSource(source: String, updates: List<MethodEvidence>) {
        val stillValid = updates.map { it.method.value }.toSet()
        val expired = amrEvidence.filter { it.source == source && it.method !in stillValid }
        if (expired.isNotEmpty()) {
            amrEvidence = amrEvidence.filterNot { it in expired }.toMutableList()
        }
        addAmr(updates)
    }
}

/** [AuthEvidence.currentAmrSource] values - see its own doc for what each means. */
object AmrSource {
    const val ORCHESTRATOR = "orchestrator"
    const val KEYCLOAK = "kc"
}

/** One method's own record within [AuthEvidence.amrEvidence] - see that field's doc for why this is one JSON list, not several parallel columns. */
data class AmrRecord(
    val method: String,
    val source: String,
    val loa: String,
    val enrolledUnderAcr: String? = null,
    val factorTypes: Set<FactorType> = emptySet(),
    /** Mirrors `orchestrator.policy.AuthEvidence.MethodEvidence.amrSourceId` - see its own doc for what this identifies and why it is never blank. */
    val amrSourceId: String,
    /** Mirrors `orchestrator.policy.AuthEvidence.MethodEvidence.axis` - see [EvidenceAxis]. Defaulted for old rows persisted before this field existed. */
    val axis: EvidenceAxis = EvidenceAxis.AUTHENTICATOR,
)

/**
 * Losslessly rebuilds the real [MethodEvidence] this record represents - INCLUDING `source`/
 * `amrSourceId`, unlike `CoreAuthEvidence.from(...)`'s flat factory (built for callers that only
 * have already-decomposed maps on hand, e.g. a wire payload) which silently defaults both away.
 * The one seam every reader of a stored [AuthEvidence] should go through.
 */
fun AmrRecord.toMethodEvidence(): MethodEvidence =
    MethodEvidence(MethodName(method), AcrLevel(loa), enrolledUnderAcr?.let(::AcrLevel), factorTypes, source, amrSourceId, axis)

/** The real, core [CoreAuthEvidence] this channel's evidence currently is - see [AmrRecord.toMethodEvidence]. */
fun AuthEvidence.toCoreEvidence(): CoreAuthEvidence = CoreAuthEvidence(amrEvidence.map { it.toMethodEvidence() })
