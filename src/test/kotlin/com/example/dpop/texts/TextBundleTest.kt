package com.example.dpop.texts

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus

class TextBundleTest : BehaviorSpec({

    val bundle = TextBundle("app")

    given("GET .../texts/{lang}") {
        val first = bundle.respond("en", null)

        then("the bundle comes with a strong ETag and must be revalidated") {
            first.statusCode shouldBe HttpStatus.OK
            first.headers.eTag shouldNotBe null
            first.headers.cacheControl shouldBe "no-cache"
            first.headers.getFirst(HttpHeaders.CONTENT_LANGUAGE) shouldBe "en"
        }

        then("asking again with that ETag answers 304 without a body") {
            val again = bundle.respond("en", first.headers.eTag)
            again.statusCode shouldBe HttpStatus.NOT_MODIFIED
            again.body shouldBe null
            again.headers.eTag shouldBe first.headers.eTag
        }

        then("another language has another ETag") {
            bundle.respond("de", first.headers.eTag).statusCode shouldBe HttpStatus.OK
        }

        then("an unknown language falls back to German, a region is ignored") {
            bundle.respond("fr", null).headers.getFirst(HttpHeaders.CONTENT_LANGUAGE) shouldBe "de"
            bundle.respond("en-GB", null).headers.getFirst(HttpHeaders.CONTENT_LANGUAGE) shouldBe "en"
        }
    }
})
