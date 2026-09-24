package com.example.dpop.orchestrator.api.v1

import com.example.dpop.texts.TextBundle
import com.example.dpop.tool_api.API_V1
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * The wordings every text reference this application sends resolves against (docs/adr/ADR-033).
 * Clients fetch it once at start and keep it with its ETag: asking again with `If-None-Match`
 * answers 304 while nothing changed, so "is there anything new?" and the download are one request.
 * No DPoP - these are the same for everyone and carry nothing about anyone.
 */
@RestController
@Tag(name = "Texts", description = "Wordings for the text references in every response, per language, with ETag")
class TextsController {

    @GetMapping("$API_V1/texts/{lang}")
    @Operation(
        summary = "Wordings of all text references in one language",
        description = "Map of text id to wording with `{name}` placeholders. `lang` is de or en (a region like en-GB " +
            "counts as en, anything else falls back to de; Content-Language says which). Send the ETag back as " +
            "If-None-Match: 304 while unchanged."
    )
    fun texts(
        @PathVariable lang: String,
        @RequestHeader(HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?
    ): ResponseEntity<Map<String, String>> = TEXTS.respond(lang, ifNoneMatch)

    private companion object {
        val TEXTS = TextBundle("app")
    }
}
