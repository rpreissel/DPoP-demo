package com.example.dpop.orchestrator.journey

import com.example.dpop.orchestrator.policy.Reachability
import com.example.dpop.orchestrator.policy.UnreachableReason
import com.example.dpop.tool_spi.FactorType

/**
 * Turns a policy-level [Reachability]/[UnreachableReason] (see their own docs) into the
 * user-facing (German) [Transition.Abort] text - the ONLY place in `orchestrator.journey` that
 * does this. `AuthPolicy` deliberately only ever produces a structured reason, never
 * pre-formatted text: this file is where a reason becomes words, so the wording lives once
 * (not once per `*Strategy` call site) and can change language/phrasing without touching any
 * reason-computing code at all.
 */
fun Reachability.NotReachable.toAbortMessage(): String =
    "Gefordertes Sicherheitsniveau ist mit den vorhandenen Methoden nicht erreichbar. ${reason.toGermanText()}"

/**
 * The AUTH-candidate-exhaustion rendering of a [Reachability] reading - shared by every caller in
 * that exact situation ([StepUpStrategy], [LookupLoginStrategy]) once [CandidateTools.forAuth] AND
 * [CandidateTools.forReIdentification] have BOTH already come back empty: only then is
 * [Reachability] itself the right question to ask, and only then does [Reachability.Reachable]
 * unambiguously mean "the account could do this, just not on THIS channel/session right now"
 * (already-used-this-session methods, a device-bound credential that doesn't match this physical
 * device, tools disabled via the demo's availability toggle, ...) rather than the (differently
 * worded) same case for an ENROLLMENT candidate list - see [toEnrollAbortMessage] for that one.
 */
fun Reachability.toAuthAbortMessage(): String = when (this) {
    is Reachability.NotReachable -> toAbortMessage()

    Reachability.Reachable ->
        "Das Konto könnte das geforderte Sicherheitsniveau grundsätzlich erreichen, aber auf diesem Kanal steht dafür gerade keine passende Methode zur Verfügung " +
            "(z. B. bereits in dieser Sitzung genutzt, für dieses Gerät deaktiviert, oder an ein anderes Gerät gebunden)."
}

/**
 * The ENROLLMENT-candidate-exhaustion rendering of a [Reachability] reading - shared by every
 * caller in that exact situation (`AuthEnrollCore`, `RegisterEnrollFirstStrategy`) once
 * [CandidateTools.forEnrollment] came back empty: [Reachability.Reachable] here means "the account
 * could still enroll something in principle, just not on THIS channel right now" - worded
 * differently from [toAuthAbortMessage]'s own `Reachable` case (AUTH vs ENROLLMENT candidates), so
 * a separate function rather than one shared string.
 */
fun Reachability.toEnrollAbortMessage(): String = when (this) {
    is Reachability.NotReachable -> toAbortMessage()

    Reachability.Reachable ->
        "Das Konto könnte das geforderte Sicherheitsniveau grundsätzlich erreichen, aber auf diesem Kanal " +
            "steht dafür gerade kein weiteres Verfahren zur Einrichtung zur Verfügung."
}

private fun UnreachableReason.toGermanText(): String = when (this) {
    UnreachableReason.NoActiveMethod -> "Für dieses Konto ist derzeit kein aktives Anmeldeverfahren eingerichtet."

    is UnreachableReason.SingleFactorType ->
        "Die aktiven Verfahren (${methods.joinToString(", ")}) decken nur einen Faktor-Typ ab " +
            "(${factorTypes.joinToString(", ") { it.toGermanText() }}). Für dieses Sicherheitsniveau " +
            "ist zusätzlich ein Verfahren mit einem ANDEREN Faktor-Typ nötig, z. B. ein Passwort (Wissen), " +
            "wenn bisher nur Besitz-Verfahren wie SMS oder E-Mail aktiv sind."

    is UnreachableReason.CombinationCapped ->
        "Die aktiven Verfahren würden in Kombination reichen, wurden aber unter einem niedrigeren " +
            "Sicherheitsniveau eingerichtet ($maxEnrolledUnderAcr) - das begrenzt, wie hoch sie gemeinsam wirken " +
            "können. Ein neues Verfahren muss erst unter dem höheren Niveau eingerichtet werden."

    is UnreachableReason.SingleMethodCapped ->
        "Das aktive Verfahren ($method) würde für sich genommen reichen, wurde " +
            "aber unter einem niedrigeren Sicherheitsniveau eingerichtet ($maxEnrolledUnderAcr) - das begrenzt, " +
            "wie hoch es wirken kann, unabhängig davon, welche Faktor-Typen es abdeckt. Es muss erst unter dem " +
            "höheren Niveau erneut eingerichtet werden (z. B. direkt im Anschluss an eine Identifizierung oder " +
            "eine bereits ausreichende Kombination anderer Verfahren)."
}

private fun FactorType.toGermanText(): String = when (this) {
    FactorType.KNOWLEDGE -> "Wissen"
    FactorType.POSSESSION -> "Besitz"
    FactorType.INHERENCE -> "Inhärenz"
}
