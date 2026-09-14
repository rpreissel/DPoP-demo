package com.example.dpop.orchestrator.journey

import com.example.dpop.orchestrator.policy.Reachability
import com.example.dpop.orchestrator.policy.UnreachableReason
import com.example.dpop.tool_spi.FactorType

/**
 * Turns a policy-level [Reachability.NotReachable]/[AuthExhaustionReason]/[UnreachableReason] (see
 * their own docs) into the user-facing (German) [Transition.Abort] text - the ONLY place in
 * `orchestrator.journey` that does this. `AuthPolicy` and [CandidateTools] deliberately only ever
 * produce a structured reason, never pre-formatted text: this file is where a reason becomes
 * words, so the wording lives once (not once per `*Strategy` call site) and can change
 * language/phrasing without touching any reason-computing code at all.
 */
fun Reachability.NotReachable.toAbortMessage(): String =
    "Gefordertes Sicherheitsniveau ist mit den vorhandenen Methoden nicht erreichbar. ${reason.toGermanText()}"

/** See [Reachability.NotReachable.toAbortMessage] - same wording, reused for the [CandidateTools.exhaustedAuthReason] case. */
fun AuthExhaustionReason.toAbortMessage(): String = when (this) {
    is AuthExhaustionReason.AccountUnreachable -> Reachability.NotReachable(reason).toAbortMessage()

    AuthExhaustionReason.ChannelLocal ->
        "Das Konto könnte das geforderte Sicherheitsniveau grundsätzlich erreichen, aber auf diesem Kanal steht dafür gerade keine passende Methode zur Verfügung " +
            "(z. B. bereits in dieser Sitzung genutzt, für dieses Gerät deaktiviert, oder an ein anderes Gerät gebunden)."
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
