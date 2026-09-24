package com.example.dpop.kcext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * One of this extension's own user-facing texts, written as its German source wording:
 * {@code KcText.t("Passwort")} (docs/adr/ADR-033). The login page never shows the template itself:
 * {@link KcTexts} looks its {@link #id()} up in the theme's {@code messages_<lang>.properties},
 * reworded per language - German included - by {@code /translate-texts}. The template must be a
 * string literal; the extension's text catalog test reads them from the compiled classes.
 */
public record KcText(String template, Map<String, String> values) {

    public static KcText t(String template) {
        return new KcText(template, Map.of());
    }

    public static KcText t(String template, Map<String, String> values) {
        return new KcText(template, values);
    }

    /** The same id as the orchestrator's {@code Text.idOf}: the first 12 hex digits of the template's SHA-256. */
    public String id() {
        return idOf(template);
    }

    public static String idOf(String template) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(template.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
