package hu.app.bmenetrend.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shares only identical work already in progress; completed responses are never cached. */
internal class EmmaInFlightRequests {
    private class Flight(val result: Deferred<String>, var readers: Int = 0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val flights = mutableMapOf<Any, Flight>()

    suspend fun execute(key: Any, request: suspend () -> String): String {
        val flight = mutex.withLock {
            val current = flights[key]?.takeUnless { it.result.isCompleted }
                ?: Flight(scope.async(start = CoroutineStart.LAZY) { request() }).also { flights[key] = it }
            current.readers++
            current
        }
        flight.result.start()
        try {
            return flight.result.await()
        } finally {
            // Cleanup also runs for cancelled readers. The HTTP call stays alive
            // while another screen still needs it, then cancels with its last reader.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                mutex.withLock {
                    flight.readers--
                    if (flight.readers == 0) {
                        if (flights[key] === flight) flights.remove(key)
                        flight.result.cancel()
                    }
                }
            }
        }
    }
}
