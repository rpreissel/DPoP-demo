package com.example.dpop.kcext;

import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The extension's own texts (docs/adr/ADR-033): every template is a literal, and every language's
 * theme bundle words each template - nothing left over, the same placeholders. Red means:
 * {@code /translate-texts <lang>}.
 */
class KcTextCatalogTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)}");

    @Test
    void everyTemplateIsALiteral() throws Exception {
        assertEquals(List.of(), KcTextCatalog.extension().problems);
    }

    @Test
    void theScannerReadsTemplateCallsAndRejectsExpressions() {
        KcTextCatalog catalog = new KcTextCatalog();
        catalog.scanTemplate("x.ftl", "<#-- t.of(ignored) -->\n<b>${t.of(\"Weiter\")}</b>\n${t.of(\"Code: {c}\", {\"c\": code})}\n${t.of(label)}");
        assertEquals(Set.of("Weiter", "Code: {c}"), catalog.entries.keySet());
        assertEquals(List.of("x.ftl:4: t.of(...) needs a string literal (placeholders as {name})"), catalog.problems);
    }

    @Test
    void everyLanguageWordsEveryTemplate() throws Exception {
        KcTextCatalog catalog = KcTextCatalog.extension();
        List<String> failures = new ArrayList<>();
        for (String language : List.of("de", "en")) {
            Properties wordings = new Properties();
            var resource = getClass().getClassLoader().getResourceAsStream("theme/orchestrator/login/messages/messages_" + language + ".properties");
            if (resource != null) {
                try (var reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
                    wordings.load(reader);
                }
            }
            for (KcTextCatalog.Entry entry : catalog.entries.values()) {
                String wording = wordings.getProperty(entry.id());
                if (wording == null) failures.add(language + ": missing " + entry.id() + " \"" + entry.template() + "\" - run /translate-texts " + language);
                else if (!placeholders(wording).equals(placeholders(entry.template()))) failures.add(language + ": placeholders differ in " + entry.id());
            }
            Set<String> known = new TreeSet<>();
            catalog.entries.values().forEach(e -> known.add(e.id()));
            for (String id : wordings.stringPropertyNames()) {
                if (!known.contains(id)) failures.add(language + ": left over " + id + " - run /translate-texts " + language);
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    /**
     * A tool is named the same on the login page and in the app: each renderer factory's title and
     * hint is word for word a frontend template - same template, same id, one wording per language.
     */
    @Test
    void toolNamesAreTheAppsOwn() throws Exception {
        String frontendCatalog = java.nio.file.Files.readString(java.nio.file.Path.of(
                System.getProperty("texts.frontendCatalog", "../frontend/build/texts-catalog.json")));
        Set<String> frontendIds = new TreeSet<>();
        Matcher id = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f]{12})\"").matcher(frontendCatalog);
        while (id.find()) frontendIds.add(id.group(1));
        List<String> differing = new ArrayList<>();
        for (KcTextCatalog.Entry entry : KcTextCatalog.extension().entries.values()) {
            boolean toolName = entry.locations().stream().anyMatch(l -> l.contains("RendererFactory.title") || l.contains("RendererFactory.hint"));
            if (toolName && !frontendIds.contains(entry.id())) differing.add("\"" + entry.template() + "\" (" + entry.locations() + ")");
        }
        // auth-qr/auth-qr-lookup exist only on the web channel - the app has no screen for them.
        differing.removeIf(d -> d.contains("AuthQrRendererFactory") || d.contains("AuthQrLookupRendererFactory"));
        assertEquals(List.of(), differing, "tool title/hint not worded like the app's - align the templates");
    }

    private static Set<String> placeholders(String wording) {
        Set<String> names = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(wording);
        while (m.find()) names.add(m.group(1));
        return names;
    }
}
