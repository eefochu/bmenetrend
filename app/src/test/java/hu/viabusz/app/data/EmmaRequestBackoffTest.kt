package hu.viabusz.app.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class EmmaRequestBackoffTest {
    private val now=Instant.parse("2026-10-23T12:00:00Z")
    @Test fun deniedRequestsCoolDownWithoutPretendingToBeEmptyData() {
        val policy=EmmaRequestBackoff()
        policy.check(now)
        assertEquals(now.plusSeconds(60),policy.denied(403,null,now))
        assertThrows(EmmaCooldownException::class.java) { policy.check(now.plusSeconds(30)) }
        policy.check(now.plusSeconds(60))
    }
    @Test fun serverRetryAfterIsHonouredAndCannotBeShortenedByAnotherFailure() {
        val policy=EmmaRequestBackoff()
        assertEquals(now.plusSeconds(120),policy.denied(429,"120",now))
        assertEquals(now.plusSeconds(120),policy.denied(403,null,now.plusSeconds(1)))
        assertEquals(Instant.parse("2026-10-23T12:05:00Z"),policy.denied(503,"Fri, 23 Oct 2026 12:05:00 GMT",now))
        assertThrows(EmmaCooldownException::class.java) { policy.check(now.plusSeconds(299)) }
        policy.check(now.plusSeconds(300))
        assertNull(policy.denied(500,null,now))
    }
}
