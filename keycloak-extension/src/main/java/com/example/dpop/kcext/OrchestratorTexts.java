package com.example.dpop.kcext;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;
import org.keycloak.models.KeycloakSession;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The orchestrator's texts, for the web channel (docs/adr/ADR-033). Every response carries text
 * references ({@code {key, args, texts}}), never wording; this resolves them in the login's
 * language against {@code GET /orchestrator/api/v1/texts/{lang}}.
 *
 * <p>One copy per orchestrator and language, kept with its ETag and revalidated with
 * {@code If-None-Match} at most once a minute - a 304 keeps the copy, so a login page costs no
 * download while nothing changed. If the orchestrator cannot be reached, the last copy stays in use;
 * without any copy a reference shows its id.
 */
final class OrchestratorTexts {

    private static final Logger LOG = Logger.getLogger(OrchestratorTexts.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Duration REVALIDATE_AFTER = Duration.ofMinutes(1);
    private static final List<String> SUPPORTED = List.of("de", "en");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)}");

    private record Bundle(String etag, Map<String, String> texts, long checkedAtNanos) {
    }

    private static final Map<String, Bundle> BUNDLES = new ConcurrentHashMap<>();

    private OrchestratorTexts() {
    }

    /** The login's language if the orchestrator writes it, otherwise German. */
    static String language(KeycloakSession session) {
        Locale locale = session.getContext().resolveLocale(null);
        String language = locale != null ? locale.getLanguage() : "de";
        return SUPPORTED.contains(language) ? language : "de";
    }

    /** {@code ref} in the login's language; null for a missing reference. */
    static String resolve(KeycloakSession session, JsonNode ref) {
        if (ref == null || ref.isNull() || !ref.hasNonNull("key")) return null;
        String baseUrl = OrchestratorSettings.of(session).orchestratorBaseUrl();
        return resolve(bundle(baseUrl, language(session)), ref);
    }

    static String resolve(Map<String, String> texts, JsonNode ref) {
        String wording = texts.getOrDefault(ref.get("key").asText(), ref.get("key").asText());
        Matcher matcher = PLACEHOLDER.matcher(wording);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            JsonNode nested = ref.path("texts").get(name);
            JsonNode plain = ref.path("args").get(name);
            String value;
            if (nested != null && nested.isArray()) {
                StringBuilder joined = new StringBuilder();
                for (JsonNode item : nested) {
                    if (!joined.isEmpty()) joined.append(", ");
                    joined.append(resolve(texts, item));
                }
                value = joined.toString();
            } else if (plain != null && !plain.isNull()) {
                value = plain.asText();
            } else {
                value = matcher.group();
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static Map<String, String> bundle(String baseUrl, String language) {
        String cacheKey = baseUrl + "|" + language;
        Bundle cached = BUNDLES.get(cacheKey);
        if (cached != null && System.nanoTime() - cached.checkedAtNanos() < REVALIDATE_AFTER.toNanos()) {
            return cached.texts();
        }
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/orchestrator/api/v1/texts/" + language))
                    .timeout(Duration.ofSeconds(5))
                    .GET();
            if (cached != null) request.header("If-None-Match", cached.etag());
            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            Bundle next;
            if (response.statusCode() == 304 && cached != null) {
                next = new Bundle(cached.etag(), cached.texts(), System.nanoTime());
            } else if (response.statusCode() == 200) {
                Map<String, String> texts = MAPPER.readValue(response.body(), new TypeReference<>() {
                });
                next = new Bundle(response.headers().firstValue("ETag").orElse(""), texts, System.nanoTime());
            } else {
                throw new IllegalStateException("texts " + language + ": HTTP " + response.statusCode());
            }
            BUNDLES.put(cacheKey, next);
            return next.texts();
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warnf("Orchestrator texts (%s) not available: %s", language, e.getMessage());
            return cached != null ? cached.texts() : Map.of();
        }
    }
}
