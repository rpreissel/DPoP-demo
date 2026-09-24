package com.example.dpop.kcext;

import org.jboss.logging.Logger;
import org.keycloak.models.KeycloakSession;
import org.keycloak.theme.Theme;

import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves this extension's own texts ({@link KcText}) in the login's language, against the login
 * theme's {@code messages/messages_<lang>.properties} - Keycloak's own bundles, so Keycloak picks
 * the language (realm internationalization, de/en). Placeholders are filled here, not by
 * MessageFormat. Without a wording the template shows (docs/adr/ADR-033).
 */
public final class KcTexts {

    private static final Logger LOG = Logger.getLogger(KcTexts.class);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)}");

    private KcTexts() {
    }

    /** Shorthand for a text resolved right where it is written: {@code KcTexts.of(session, "Abgebrochen.")}. */
    public static String of(KeycloakSession session, String template) {
        return resolve(session, KcText.t(template));
    }

    public static String resolve(KeycloakSession session, KcText text) {
        if (text == null) return null;
        return resolve(messages(session), text.template(), text.values());
    }

    static String resolve(Properties messages, String template, Map<String, ?> values) {
        String wording = messages.getProperty(KcText.idOf(template), template);
        Matcher matcher = PLACEHOLDER.matcher(wording);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            Object value = values != null ? values.get(matcher.group(1)) : null;
            matcher.appendReplacement(out, Matcher.quoteReplacement(value != null ? value.toString() : matcher.group()));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** What a template calls as {@code t}: {@code ${t.of("Weiter")}}, {@code ${t.of("Demo-Code: {code}", {"code": demoTan})}}. */
    public static TemplateTexts forTemplates(KeycloakSession session) {
        return new TemplateTexts(messages(session));
    }

    public static final class TemplateTexts {
        private final Properties messages;

        TemplateTexts(Properties messages) {
            this.messages = messages;
        }

        public String of(String template) {
            return resolve(messages, template, Map.of());
        }

        public String of(String template, Map<String, ?> values) {
            return resolve(messages, template, values);
        }
    }

    private static Properties messages(KeycloakSession session) {
        try {
            Locale locale = session.getContext().resolveLocale(null);
            Theme theme = session.theme().getTheme(Theme.Type.LOGIN);
            return theme.getEnhancedMessages(session.getContext().getRealm(), locale != null ? locale : Locale.GERMAN);
        } catch (Exception e) {
            LOG.warnf("Login theme messages not available: %s", e.getMessage());
            return new Properties();
        }
    }
}
