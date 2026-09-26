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

    /**
     * The same id as the orchestrator's {@code Text.idOf}: the template's first words as a slug, then
     * the first 6 hex digits of its SHA-256 ({@code journey-trace-laden-fehlgeschlagen-cf9829}).
     */
    public String id() {
        return idOf(template);
    }

    public static String idOf(String template) {
        String hash;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(template.getBytes(StandardCharsets.UTF_8));
            hash = HexFormat.of().formatHex(digest).substring(0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        String slug = slugOf(template);
        return slug.isEmpty() ? hash : slug + "-" + hash;
    }

    private static final int SLUG_MAX = 40;

    /** See {@code Text.slugOf} - the same rule, character for character. */
    private static String slugOf(String template) {
        String words = template.toLowerCase(java.util.Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (words.length() <= SLUG_MAX) return words;
        int cut = words.substring(0, SLUG_MAX + 1).lastIndexOf('-');
        return (cut > 0 ? words.substring(0, cut) : words.substring(0, SLUG_MAX)).replaceAll("^-+|-+$", "");
    }
}
