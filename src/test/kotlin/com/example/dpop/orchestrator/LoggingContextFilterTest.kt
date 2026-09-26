package com.example.dpop.orchestrator

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.slf4j.MDC
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/** B-9: the log context a request runs with, and that it does not outlive the request. */
class LoggingContextFilterTest : BehaviorSpec({
    val channel = "3fa85f64-5717-4562-b3fc-2c963f66afa6"
    val tool = "9c858901-8a57-4791-81fe-4c455b099bc9"

    given("request paths") {
        then("a channel path names the channel, a tool path the tool session, others nothing") {
            LoggingContextFilter.contextOf("/orchestrator/api/v1/app/channels/$channel/tools/enroll-sms") shouldBe
                mapOf(LoggingContextFilter.CHANNEL_SESSION_ID to channel)
            LoggingContextFilter.contextOf("/orchestrator/api/v1/tools/$tool/enroll-sms") shouldBe
                mapOf(LoggingContextFilter.TOOL_SESSION_ID to tool)
            LoggingContextFilter.contextOf("/orchestrator/demo/server-info").shouldBeEmpty()
        }
    }

    given("a request through the filter") {
        then("the ids are set while it runs and gone afterwards") {
            var seen: Map<String, String?> = emptyMap()
            val chain = MockFilterChain(object : jakarta.servlet.http.HttpServlet() {
                override fun service(req: jakarta.servlet.ServletRequest, res: jakarta.servlet.ServletResponse) {
                    seen = mapOf(
                        "channel" to MDC.get(LoggingContextFilter.CHANNEL_SESSION_ID),
                        "request" to MDC.get(LoggingContextFilter.REQUEST_ID),
                    )
                }
            })
            LoggingContextFilter().doFilter(MockHttpServletRequest("GET", "/orchestrator/api/v1/app/channels/$channel"), MockHttpServletResponse(), chain)

            seen["channel"] shouldBe channel
            seen["request"] shouldNotBe null
            MDC.get(LoggingContextFilter.CHANNEL_SESSION_ID) shouldBe null
            MDC.get(LoggingContextFilter.REQUEST_ID) shouldBe null
        }
    }
})
