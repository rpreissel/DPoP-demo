package com.example.dpop.orchestrator.dpop

import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.util.Base64URL
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

@Component
class JwkThumbprintService {

    fun computeThumbprint(jwk: JWK): String =
        computeBase64UrlThumbprint(jwk).toString()

    fun computeBase64UrlThumbprint(jwk: JWK): Base64URL {
        return try {
            val requiredMembers = extractRequiredMembers(jwk)
            val canonicalJson = toCanonicalJson(requiredMembers)
            val hash = MessageDigest.getInstance(HASH_ALGORITHM)
                .digest(canonicalJson.toByteArray(StandardCharsets.UTF_8))
            Base64URL.encode(hash)
        } catch (e: NoSuchAlgorithmException) {
            throw DpopValidationException("Failed to compute JWK thumbprint", e)
        }
    }

    /**
     * RFC 7638 SS3.2/3.3 requires the JSON member names in LEXICOGRAPHIC order, not the order
     * they happen to appear in the source JWK - hence the [sortedMapOf] rather than a
     * [LinkedHashMap]. Getting this wrong doesn't break the demo internally (every comparison
     * here is against a value this same method computed), but produces a thumbprint that would
     * NOT match a standards-conformant peer's (e.g. a real Keycloak `cnf.jkt`).
     */
    private fun extractRequiredMembers(jwk: JWK): Map<String, Any> {
        val members = jwk.toJSONObject()
        val required = sortedMapOf<String, Any>()
        val kty = members["kty"] as String?
            ?: throw DpopValidationException("JWK is missing kty")
        required["kty"] = kty

        when (kty) {
            "EC" -> {
                copyIfPresent(members, required, "crv")
                copyIfPresent(members, required, "x")
                copyIfPresent(members, required, "y")
            }
            "RSA" -> {
                copyIfPresent(members, required, "n")
                copyIfPresent(members, required, "e")
            }
            "oct" -> copyIfPresent(members, required, "k")
            else -> throw DpopValidationException("Unsupported key type for thumbprint: $kty")
        }
        return required
    }

    private fun copyIfPresent(source: Map<String, Any>, target: MutableMap<String, Any>, key: String) {
        val value = source[key]
            ?: throw DpopValidationException("JWK is missing required member: $key")
        target[key] = value
    }

    private fun toCanonicalJson(map: Map<String, Any>): String = buildString {
        append("{")
        map.entries.forEachIndexed { index, (k, v) ->
            if (index > 0) append(",")
            append("\"").append(escapeJson(k)).append("\":")
            append("\"").append(escapeJson(v.toString())).append("\"")
        }
        append("}")
    }

    private fun escapeJson(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\u0008", "\\b")
        .replace("\u000C", "\\f")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    companion object {
        private const val HASH_ALGORITHM = "SHA-256"
    }
}
