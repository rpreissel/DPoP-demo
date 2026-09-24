package com.example.dpop.orchestrator.journey

import com.example.dpop.texts.Text
import com.example.dpop.orchestrator.policy.Reachability
import com.example.dpop.orchestrator.policy.UnreachableReason
import com.example.dpop.tool_spi.FactorType

/**
 * Turns a policy-level [Reachability]/[UnreachableReason] (see their own docs) into the
 * user-facing [Transition.Abort] text (a [Text], worded per language in the bundles) - the ONLY place in `orchestrator.journey` that
 * does this. `AuthPolicy` deliberately only ever produces a structured reason, never
 * pre-formatted text: this file is where a reason becomes words, so the wording lives once
 * (not once per `*Strategy` call site) and can change language/phrasing without touching any
 * reason-computing code at all.
 */
fun Reachability.NotReachable.toAbortMessage(): Text =
    Text("Mit Ihren Anmeldeverfahren ist das nötige Sicherheitsniveau nicht erreichbar. {grund}", "grund" to reason.toText())

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
fun Reachability.toAuthAbortMessage(): Text = when (this) {
    is Reachability.NotReachable -> toAbortMessage()

    Reachability.Reachable -> Text(
        "Hier steht gerade kein passendes Anmeldeverfahren zur Verfügung - etwa weil es in dieser Sitzung schon genutzt " +
            "wurde oder an ein anderes Gerät gebunden ist."
    )
}

/**
 * The ENROLLMENT-candidate-exhaustion rendering of a [Reachability] reading - shared by every
 * caller in that exact situation (`AuthEnrollCore`, `RegisterEnrollFirstStrategy`) once
 * [CandidateTools.forEnrollment] came back empty: [Reachability.Reachable] here means "the account
 * could still enroll something in principle, just not on THIS channel right now" - worded
 * differently from [toAuthAbortMessage]'s own `Reachable` case (AUTH vs ENROLLMENT candidates), so
 * a separate function rather than one shared string.
 */
fun Reachability.toEnrollAbortMessage(): Text = when (this) {
    is Reachability.NotReachable -> toAbortMessage()

    Reachability.Reachable -> Text("Hier steht gerade kein weiteres Verfahren zur Einrichtung zur Verfügung.")
}

private fun UnreachableReason.toText(): Text = when (this) {
    UnreachableReason.NoActiveMethod -> Text("Für dieses Konto ist derzeit kein aktives Anmeldeverfahren eingerichtet.")

    is UnreachableReason.SingleFactorType -> Text(
        "Ihre Verfahren ({methods}) sind alle von derselben Art ({faktoren}). Richten Sie zusätzlich ein Verfahren " +
            "anderer Art ein, zum Beispiel ein Passwort.",
        "methods" to methods.joinToString(", "),
        "faktoren" to factorTypes.map { it.toText() }
    )

    // Not which level they were set up under - the reader needs only what to do next.
    is UnreachableReason.CombinationCapped ->
        Text("Ihre Verfahren wurden mit einem niedrigeren Sicherheitsniveau eingerichtet. Identifizieren Sie sich und richten Sie danach ein neues Verfahren ein.")

    is UnreachableReason.SingleMethodCapped -> Text(
        "Das Verfahren {method} wurde mit einem niedrigeren Sicherheitsniveau eingerichtet. Identifizieren Sie sich " +
            "und richten Sie es danach erneut ein.",
        "method" to method
    )
}

private fun FactorType.toText(): Text = when (this) {
    FactorType.KNOWLEDGE -> Text("Wissen")
    FactorType.POSSESSION -> Text("Besitz")
    FactorType.INHERENCE -> Text("Inhärenz")
}
