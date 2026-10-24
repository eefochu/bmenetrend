package hu.app.bmenetrend.data

import java.io.IOException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class EmmaHttpException(val status: Int, val retryAt: Instant?) : IOException("EMMA HTTP $status")
class EmmaCooldownException(val retryAt: Instant) : IOException("EMMA requests are cooling down until $retryAt")

/** Respect denied/rate-limited responses across every EMMA client, including the vehicle poller. */
class EmmaRequestBackoff {
    private var until: Instant? = null
    @Synchronized fun check(now: Instant) {
        until?.takeIf { it > now }?.let { throw EmmaCooldownException(it) }
    }
    @Synchronized fun denied(status: Int, retryAfter: String?, now: Instant): Instant? {
        if (status !in setOf(403, 429, 503)) return null
        val parsed = retryAfter?.toLongOrNull()?.takeIf { it >= 0 }?.let { runCatching { now.plusSeconds(it) }.getOrNull() }
            ?: retryAfter?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull() }
        val retry = maxOf(now.plusSeconds(60), parsed ?: now)
        until = maxOf(until ?: retry, retry)
        return until
    }
}
