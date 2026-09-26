package com.example.dpop.orchestrator.kc

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException

/**
 * Die Schluesselpaare dieses Knotens, je Zweck genau eines ([NodeSigningKey]). Liegen in der
 * Datenbank, nicht im Prozessspeicher: mehrere Instanzen tragen so dieselbe Identitaet, und ein
 * Neustart aendert sie nicht. Erzeugt wird ein Paar beim ersten Zugriff; starten zwei Instanzen
 * gleichzeitig, laeuft die zweite in den Primaerschluessel und liest das bereits angelegte Paar.
 */
class NodeKeys(private val repository: NodeSigningKeyRepository) {
    private val cache = ConcurrentHashMap<String, ECKey>()

    /** Das Paar fuer [purpose]; [keyIdPrefix] beginnt die kid eines neu erzeugten. */
    fun keyFor(purpose: String, keyIdPrefix: String): ECKey =
        cache.computeIfAbsent(purpose) { loadOrCreate(it, keyIdPrefix) }

    private fun loadOrCreate(purpose: String, keyIdPrefix: String): ECKey {
        stored(purpose)?.let { return it }
        val generated = ECKeyGenerator(Curve.P_256)
            .keyID("$keyIdPrefix-" + Instant.now().toEpochMilli())
            .algorithm(JWSAlgorithm.ES256)
            .keyUse(KeyUse.SIGNATURE)
            .generate()
        return try {
            repository.insert(
                purpose = purpose,
                publicKeyJwk = generated.toPublicJWK().toJSONString(),
                privateKeyJwk = generated.toJSONString(),
                createdAt = Instant.now(),
            )
            generated
        } catch (e: DataIntegrityViolationException) {
            // Eine zweite Instanz war schneller - ihr Paar gilt, nicht das gerade erzeugte.
            log.info("Schluessel fuer {} wurde parallel angelegt, uebernehme den vorhandenen", purpose, e)
            stored(purpose) ?: throw e
        }
    }

    private fun stored(purpose: String): ECKey? =
        repository.findById(purpose).orElse(null)?.let { ECKey.parse(it.privateKeyJwk) }

    private companion object {
        val log = LoggerFactory.getLogger(NodeKeys::class.java)
    }
}
