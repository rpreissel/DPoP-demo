package com.example.dpop.orchestrator.dpop;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.util.Base64URL;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class JwkThumbprintService {

    private static final String HASH_ALGORITHM = "SHA-256";

    public String computeThumbprint(JWK jwk) {
        return computeBase64UrlThumbprint(jwk).toString();
    }

    public Base64URL computeBase64UrlThumbprint(JWK jwk) {
        try {
            Map<String, Object> requiredMembers = extractRequiredMembers(jwk);
            String canonicalJson = toCanonicalJson(requiredMembers);
            byte[] hash = MessageDigest.getInstance(HASH_ALGORITHM)
                    .digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            return Base64URL.encode(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new DpopValidationException("Failed to compute JWK thumbprint", e);
        }
    }

    private Map<String, Object> extractRequiredMembers(JWK jwk) {
        Map<String, Object> members = jwk.toJSONObject();
        Map<String, Object> required = new LinkedHashMap<>();
        String kty = (String) members.get("kty");
        if (kty == null) {
            throw new DpopValidationException("JWK is missing kty");
        }
        required.put("kty", kty);

        switch (kty) {
            case "EC" -> {
                copyIfPresent(members, required, "crv");
                copyIfPresent(members, required, "x");
                copyIfPresent(members, required, "y");
            }
            case "RSA" -> {
                copyIfPresent(members, required, "n");
                copyIfPresent(members, required, "e");
            }
            case "oct" -> copyIfPresent(members, required, "k");
            default -> throw new DpopValidationException("Unsupported key type for thumbprint: " + kty);
        }
        return required;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value == null) {
            throw new DpopValidationException("JWK is missing required member: " + key);
        }
        target.put(key, value);
    }

    private String toCanonicalJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
            sb.append("\"").append(escapeJson(String.valueOf(entry.getValue()))).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
