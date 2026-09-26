package com.example.dpop.tool_api

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/** RFC 9449 4.3, review 2026-09-26 F-10: scheme and host without case, default port, path exact. */
class HtuMatchesTest : BehaviorSpec({
    val request = "https://api.example.org/orchestrator/api/v1/tools/abc/auth-sms"

    given("the same target written differently") {
        then("it matches - query, fragment, case of scheme and host, an explicit default port") {
            htuMatches("https://api.example.org/orchestrator/api/v1/tools/abc/auth-sms?x=1#f", request) shouldBe true
            htuMatches("HTTPS://API.Example.ORG/orchestrator/api/v1/tools/abc/auth-sms", request) shouldBe true
            htuMatches("https://api.example.org:443/orchestrator/api/v1/tools/abc/auth-sms", request) shouldBe true
        }
    }

    given("a different target") {
        then("it does not match - another path case, path, port, scheme, or no URL at all") {
            htuMatches("https://api.example.org/Orchestrator/api/v1/tools/abc/auth-sms", request) shouldBe false
            htuMatches("https://api.example.org/orchestrator/api/v1/tools/abd/auth-sms", request) shouldBe false
            htuMatches("https://api.example.org:8443/orchestrator/api/v1/tools/abc/auth-sms", request) shouldBe false
            htuMatches("http://api.example.org/orchestrator/api/v1/tools/abc/auth-sms", request) shouldBe false
            htuMatches("/orchestrator/api/v1/tools/abc/auth-sms", request) shouldBe false
            htuMatches("ftp://api.example.org/orchestrator/api/v1/tools/abc/auth-sms", request) shouldBe false
        }
    }
})
