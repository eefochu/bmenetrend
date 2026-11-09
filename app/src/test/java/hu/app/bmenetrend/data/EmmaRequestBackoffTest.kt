package hu.app.bmenetrend.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class EmmaRequestBackoffTest {
    private val now = Instant.parse("2026-10-23T12:00:00Z")

    @Test fun deniedRequestsCoolDownWithoutPretendingToBeEmptyData() {
        val policy = EmmaRequestBackoff()
        policy.check(now)
        assertEquals(now.plusSeconds(5), policy.denied(403, null, now))
        assertThrows(EmmaCooldownException::class.java) { policy.check(now.plusSeconds(4)) }
        policy.check(now.plusSeconds(5))
    }

    @Test fun consecutiveHeaderlessDenialsEscalateAndCapAt60Seconds() {
        val policy = EmmaRequestBackoff()
        var current = now
        val expectedDelays = listOf(5L, 10L, 20L, 40L, 60L, 60L)
        for (expectedDelay in expectedDelays) {
            policy.check(current)
            val until = policy.denied(403, null, current)
            val expectedUntil = current.plusSeconds(expectedDelay)
            assertEquals(expectedUntil, until)
            assertThrows(EmmaCooldownException::class.java) {
                policy.check(expectedUntil.minusSeconds(1))
            }
            policy.check(expectedUntil)
            current = expectedUntil
        }
    }

    @Test fun succeededResetsEscalationWhilePreservingActiveCooldown() {
        val policy = EmmaRequestBackoff()
        assertEquals(now.plusSeconds(5), policy.denied(403, null, now))
        val t1 = now.plusSeconds(5)
        assertEquals(t1.plusSeconds(10), policy.denied(429, null, t1))
        val t2 = t1.plusSeconds(10)
        assertEquals(t2.plusSeconds(20), policy.denied(503, null, t2))
        val unexpiredCooldown = t2.plusSeconds(20)

        // succeeded() resets strikes but does not clear active cooldown
        policy.succeeded()
        assertThrows(EmmaCooldownException::class.java) { policy.check(unexpiredCooldown.minusSeconds(1)) }
        policy.check(unexpiredCooldown)

        // Next denial starts from 5 seconds again
        assertEquals(unexpiredCooldown.plusSeconds(5), policy.denied(403, null, unexpiredCooldown))
    }

    @Test fun resetClearsActiveCooldownAndEscalation() {
        val policy = EmmaRequestBackoff()
        assertEquals(now.plusSeconds(5), policy.denied(403, null, now))
        assertThrows(EmmaCooldownException::class.java) { policy.check(now.plusSeconds(1)) }

        policy.reset()
        // Active cooldown is cleared immediately
        policy.check(now.plusSeconds(1))

        // Escalation is also reset to 5 seconds
        assertEquals(now.plusSeconds(6), policy.denied(403, null, now.plusSeconds(1)))
    }

    @Test fun serverRetryAfterIsHonouredAndCannotBeShortenedByAnotherFailure() {
        val policy = EmmaRequestBackoff()
        assertEquals(now.plusSeconds(120), policy.denied(429, "120", now))
        assertEquals(now.plusSeconds(120), policy.denied(403, null, now.plusSeconds(1)))
        assertEquals(Instant.parse("2026-10-23T12:05:00Z"), policy.denied(503, "Fri, 23 Oct 2026 12:05:00 GMT", now))
        assertThrows(EmmaCooldownException::class.java) { policy.check(now.plusSeconds(299)) }
        policy.check(now.plusSeconds(300))
        assertNull(policy.denied(500, null, now))

        // A shorter Retry-After or denial cannot shorten an existing longer cooldown
        val activePolicy = EmmaRequestBackoff()
        val longUntil = activePolicy.denied(503, "50", now)
        assertEquals(now.plusSeconds(50), longUntil)
        val afterShorterDenial = activePolicy.denied(403, "2", now.plusSeconds(1))
        assertEquals(now.plusSeconds(50), afterShorterDenial)
    }
}
