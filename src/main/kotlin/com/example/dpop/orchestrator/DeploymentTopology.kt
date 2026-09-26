package com.example.dpop.orchestrator

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * States, in one place, that this system currently runs as a SINGLE instance - and refuses to
 * start quietly if it is told otherwise.
 *
 * The assumption was real but unwritten. It lived in three unrelated places, each documented only
 * for itself:
 *
 *  - three `@Scheduled` jobs (tool-session sweep, `RetentionJob`, DPoP replay cleanup) with no lock or
 *    leader election, so every instance would run every sweep concurrently;
 *  - `dpop.secrets.otp-pepper` blank by default, meaning a fresh random pepper per boot - two
 *    instances then cannot verify each other's SMS/e-mail codes at all;
 *  - `KeycloakAdminClient`'s `@Volatile` token and component-id caches, per process by nature;
 *  - `KeycloakAccountSyncListener` serializing all syncs on one in-process thread.
 *
 * Every one of those is individually explained where it stands; none of them says "therefore this
 * runs once". So the limit would not have been discovered by reading the code - it would have been
 * discovered by a second pod, as intermittently failing TAN checks and doubled retention sweeps,
 * which is the worst possible place to learn it.
 *
 * `deployment.instances=multiple` is therefore not a switch that enables anything. It is a claim
 * about the environment, and this class checks the claim against what the code actually supports.
 * When support arrives (a shared scheduler lock, a configured pepper), the check is where it gets
 * relaxed - deliberately, not by accident.
 */
@ConfigurationProperties(prefix = "deployment")
data class DeploymentProperties(
    /**
     * `single` (default) or `multiple`. A deployment that genuinely runs more than one instance
     * must say so, and will then be told what is still missing rather than misbehaving silently.
     */
    val instances: Instances = Instances.SINGLE
) {
    enum class Instances { SINGLE, MULTIPLE }
}

@Component
class DeploymentTopologyCheck(
    private val properties: DeploymentProperties,
    @Value("\${dpop.secrets.otp-pepper:}") private val otpPepper: String
) {
    private val log = LoggerFactory.getLogger(DeploymentTopologyCheck::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun check() {
        if (properties.instances == DeploymentProperties.Instances.SINGLE) {
            log.info(
                "Deployment: eine Instanz (deployment.instances=single). Geplante Jobs laufen ohne " +
                    "Sperre, das OTP-Pepper darf leer bleiben."
            )
            return
        }

        val missing = buildList {
            if (otpPepper.isBlank()) {
                add(
                    "dpop.secrets.otp-pepper ist leer - dann erzeugt jede Instanz beim Start ein eigenes " +
                        "zufaelliges Pepper, und keine kann die SMS-/E-Mail-Codes der anderen pruefen. " +
                        "Einen gemeinsamen Wert setzen."
                )
            }
            // No conditional: nothing in this codebase coordinates the schedulers yet, so declaring
            // MULTIPLE is always wrong on this point until something does. Better to say so than to
            // let the scheduled jobs run N times over.
            add(
                "Der Keycloak-Account-Sync serialisiert seine Syncs nur prozessintern (ein Thread) - " +
                    "zwei Instanzen legen denselben Keycloak-User und dasselbe Keypair parallel an."
            )
            add(
                "Die geplanten Jobs (Retention der Sitzungen, Tool-Sessions, Replay-Schutz, " +
                    "Aenderungs- und Anmeldeprotokoll) haben keine Leader-Election und keine Sperre - " +
                    "bei mehreren Instanzen laufen sie mehrfach parallel. Dafuer fehlt die Umsetzung " +
                    "noch (docs/07-betrieb.md)."
            )
            add(
                "RestoreDataCodec erzeugt sein Signaturgeheimnis je Prozess - ein RestoreData-Token " +
                    "einer Instanz ist fuer die andere unlesbar (Web-Anmeldung muss neu beginnen)."
            )
        }

        error(
            "deployment.instances=multiple, aber dieses System traegt das noch nicht:\n" +
                missing.joinToString("\n") { "  - $it" }
        )
    }
}
