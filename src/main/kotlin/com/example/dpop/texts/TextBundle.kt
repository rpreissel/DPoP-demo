package com.example.dpop.texts

import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.security.MessageDigest
import java.util.Properties

/** The languages every bundle is written in; the first is the fallback for anything else asked for. */
val SUPPORTED_LANGUAGES = listOf("de", "en")

/**
 * One bundle's wordings per language, from `texts/<name>/texts_<lang>.properties` (UTF-8): a
 * [Text] id to what a reader of that language sees. A bundle is what one kind of client resolves
 * against - `app` for this application's clients, one each for the simulated foreign systems.
 * The files are written by `/translate-texts` and checked complete by `TextTranslationsTest`.
 */
class TextBundle(val name: String) {

    private val byLanguage: Map<String, Served> = SUPPORTED_LANGUAGES.associateWith { load(it) }

    init {
        loaded += this
    }

    companion object {
        private val loaded = java.util.concurrent.CopyOnWriteArrayList<TextBundle>()

        /**
         * Whether some bundle has a wording for [id] in every language - if not, a [Text] carries its
         * template along (`TextRef.template`), so a reader sees the developer's wording instead of an
         * id until `/translate-texts` has run (ADR-33).
         */
        fun wordedEverywhere(id: String): Boolean = loaded.any { bundle -> bundle.byLanguage.values.all { id in it.texts } }
    }

    /** The texts in [language], or in the fallback language if that one is not written. */
    fun texts(language: String): Map<String, String> = served(language).texts

    /**
     * `GET .../texts/{lang}`: the whole bundle with a strong ETag over its content, or 304 when the
     * client's [ifNoneMatch] still names it - so asking "is there anything new?" and fetching it
     * are one request. `no-cache` makes every start ask again rather than trust a stale copy.
     */
    fun respond(language: String, ifNoneMatch: String?): ResponseEntity<Map<String, String>> {
        val served = served(language)
        val matches = ifNoneMatch?.split(",")?.map { it.trim().removePrefix("W/") }?.any { it == served.etag || it == "*" } == true
        val builder = (if (matches) ResponseEntity.status(HttpStatus.NOT_MODIFIED) else ResponseEntity.ok())
            .eTag(served.etag)
            .cacheControl(CacheControl.noCache())
            .header(HttpHeaders.CONTENT_LANGUAGE, served.language)
        return if (matches) builder.build() else builder.body(served.texts)
    }

    private fun served(language: String): Served =
        byLanguage[language.lowercase().substringBefore('-')] ?: byLanguage.getValue(SUPPORTED_LANGUAGES.first())

    private fun load(language: String): Served {
        val texts = sortedMapOf<String, String>()
        PathMatchingResourcePatternResolver().getResources("classpath*:texts/$name/texts_$language.properties").forEach { resource ->
            val properties = Properties().apply { resource.inputStream.reader(Charsets.UTF_8).use { load(it) } }
            properties.stringPropertyNames().forEach { key ->
                check(texts.put(key, properties.getProperty(key)) == null) { "Text id $key appears twice in bundle $name ($language)" }
            }
        }
        check(texts.isNotEmpty()) { "Text bundle $name has no texts for $language" }
        val digest = MessageDigest.getInstance("SHA-256").digest(texts.entries.joinToString("\n") { "${it.key}=${it.value}" }.toByteArray())
        return Served(language, texts, "\"" + digest.take(16).joinToString("") { "%02x".format(it) } + "\"")
    }

    private class Served(val language: String, val texts: Map<String, String>, val etag: String)
}
