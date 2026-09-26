package com.example.dpop.sms_mock

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/** One SMS the simulated provider "sent". */
data class SentSms(val sequence: Long, val phoneNumber: String, val tan: String, val sentAt: Instant)

/**
 * The simulated SMS provider. Nothing leaves the process: a sent SMS lands in an in-memory
 * [outbox] (the newest [OUTBOX_SIZE]), like the Personenverzeichnis' letter box.
 *
 * It never writes the TAN to a log (review 2026-09, M-11). The six `println`s this replaces put
 * code and recipient on STDOUT regardless of demo mode; what the demo shows the tester goes
 * through the tool's own `demo` block, which that switch controls. The log line names only the
 * last digits of the number.
 */
@Service
class SmsGateway {

    private val outbox = ConcurrentLinkedDeque<SentSms>()
    private val sequence = AtomicLong()

    fun sendTan(phoneNumber: String, tan: String) {
        outbox.addFirst(SentSms(sequence.incrementAndGet(), phoneNumber, tan, Instant.now()))
        while (outbox.size > OUTBOX_SIZE) outbox.pollLast()
        log.info("Simulated SMS sent to {}", masked(phoneNumber))
    }

    /** Newest first. */
    fun outbox(): List<SentSms> = outbox.toList()

    private fun masked(phoneNumber: String): String =
        "***" + phoneNumber.filter(Char::isDigit).takeLast(3)

    private companion object {
        const val OUTBOX_SIZE = 100
        val log = LoggerFactory.getLogger(SmsGateway::class.java)
    }
}
