package com.example.dpop.texts

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonValue
import io.swagger.v3.oas.annotations.media.Schema
import java.security.MessageDigest

/**
 * A user-facing text, written in the code as its German source wording: `Text("TAN ungültig")`,
 * with named `{placeholders}` for what varies - `Text("Dieser {typ}-Wert …", "typ" to type)`.
 *
 * The source wording is not what anyone reads. Before it leaves the backend it becomes a
 * reference ([TextRef]): the [id] of the template plus the values. Every language - German
 * included - is a reworded copy in `texts/<bundle>/texts_<lang>.properties`, written from the
 * templates by `/translate-texts` (docs/adr/ADR-033). Clients resolve references against the bundle
 * they fetched from `.../texts/{lang}`.
 *
 * The template must be a string literal (constants and `+`-joined literals are fine): the texts are
 * collected from the compiled classes (`TextCatalog`), which fails the build for anything else.
 *
 * Arguments: a [Text] or a list of them is itself translated (several joined with ", "); anything
 * else is shown as it is - ids, numbers, method names.
 */
@Schema(implementation = TextRef::class)
class Text(template: String, vararg args: Pair<String, Any?>) {

    val template: String = template

    /** Values shown as they are. */
    val args: Map<String, String>

    /** Values that are texts themselves. */
    val texts: Map<String, List<Text>>

    init {
        val plain = linkedMapOf<String, String>()
        val nested = linkedMapOf<String, List<Text>>()
        args.forEach { (name, value) ->
            when {
                value is Text -> nested[name] = listOf(value)
                value is List<*> && value.all { it is Text } -> nested[name] = value.map { it as Text }
                else -> plain[name] = value?.toString().orEmpty()
            }
        }
        this.args = plain
        this.texts = nested
    }

    val id: String get() = idOf(template)

    /** What goes over the wire - the reference, never the template. */
    @JsonValue
    fun toRef(): TextRef {
        onWire?.invoke(this)
        return TextRef(id, args.ifEmpty { null }, texts.ifEmpty { null }?.mapValues { (_, list) -> list.map { it.toRef() } })
    }

    override fun equals(other: Any?): Boolean =
        other is Text && other.template == template && other.args == args && other.texts == texts

    override fun hashCode(): Int = listOf(template, args, texts).hashCode()

    override fun toString(): String = "Text($template, $args, $texts)"

    companion object {
        /** The reference of a template: the first 12 hex digits of its SHA-256 - the same template, the same id. */
        fun idOf(template: String): String =
            MessageDigest.getInstance("SHA-256").digest(template.toByteArray()).take(6).joinToString("") { "%02x".format(it) }

        /** The placeholder names a template or wording uses - the same set in every language. */
        fun placeholdersOf(wording: String): Set<String> =
            Regex("\\{([A-Za-z][A-Za-z0-9_]*)}").findAll(wording).map { it.groupValues[1] }.toSet()

        /** Tests hook in here to check every reference sent out against the collected catalog. */
        @Volatile
        internal var onWire: ((Text) -> Unit)? = null
    }
}

/** A [Text] as clients see it: resolve [key] in the texts bundle, fill `{name}` from [args] as is and from [texts] translated. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A text reference: look `key` up in the texts bundle (GET .../texts/{lang}), fill `{name}` placeholders from `args` (as is) or `texts` (resolved the same way, several joined with \", \").")
data class TextRef(
    @field:Schema(example = "3f9a1c0b2e7d") val key: String,
    /** Absent when the text has no plain values. */
    val args: Map<String, String>? = null,
    /** Absent when the text has no nested texts. */
    val texts: Map<String, List<TextRef>>? = null
)
