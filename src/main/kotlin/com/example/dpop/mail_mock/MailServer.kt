package com.example.dpop.mail_mock

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/** One mail the simulated server "sent". */
data class SentMail(val sequence: Long, val address: String, val code: String, val sentAt: Instant)

/**
 * The simulated mail server. Nothing leaves the process: a sent mail lands in an in-memory
 * [outbox] (the newest [OUTBOX_SIZE]), like the Personenverzeichnis' letter box.
 *
 * It never writes the code to a log (review 2026-09, M-11). The `println`s this replaces put code
 * and address on STDOUT regardless of `demo.disclosure`; what the demo shows the tester goes
 * through the tool's own `demo` block, which that switch controls. The log line names only the
 * domain of the address.
 */
@Service
class MailServer {

    private val outbox = ConcurrentLinkedDeque<SentMail>()
    private val sequence = AtomicLong()

    fun sendCode(address: String, code: String) {
        outbox.addFirst(SentMail(sequence.incrementAndGet(), address, code, Instant.now()))
        while (outbox.size > OUTBOX_SIZE) outbox.pollLast()
        log.info("Simulated mail sent to ***@{}", address.substringAfter('@', "?"))
    }

    /** Newest first. */
    fun outbox(): List<SentMail> = outbox.toList()

    private companion object {
        const val OUTBOX_SIZE = 100
        val log = LoggerFactory.getLogger(MailServer::class.java)
    }
}
