package hu.app.bmenetrend.offline

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

class OfflineDatabaseThreadTest {
    /** Force a different caller thread on every resume, as Dispatchers.IO is allowed to do. */
    private class SwitchingDispatcher : CoroutineDispatcher(), AutoCloseable {
        private val turn = AtomicInteger()
        private val threads = Array(2) { index -> Executors.newSingleThreadExecutor { Thread(it, "Caller-$index") } }
        override fun dispatch(context: CoroutineContext, block: Runnable) { threads[turn.getAndIncrement() % 2].execute(block) }
        override fun close() { threads.forEach { it.shutdown() } }
    }

    @Test fun transactionOwnerSurvivesSuspendingProgressUpdates() = runBlocking {
        SwitchingDispatcher().use { caller ->
            withContext(caller) {
                onOfflineDatabaseThread {
                    val owner = Thread.currentThread()
                    repeat(20) {
                        yield()
                        assertSame("SQLite transaction resumed on a different thread", owner, Thread.currentThread())
                    }
                }
            }
        }
    }
    @Test fun cancellationRunsTransactionCleanupOnItsOwnerThread() = runBlocking {
        SwitchingDispatcher().use { caller ->
            withContext(caller) {
                val started = CompletableDeferred<Thread>()
                val cleaned = CompletableDeferred<Thread>()
                val job = launch {
                    onOfflineDatabaseThread {
                        started.complete(Thread.currentThread())
                        try { awaitCancellation() }
                        finally { cleaned.complete(Thread.currentThread()) }
                    }
                }
                val owner = started.await()
                job.cancelAndJoin()
                assertSame("SQLite rollback moved to a different thread", owner, cleaned.await())
            }
        }
    }

}
