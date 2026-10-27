package hu.app.bmenetrend.offline

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Android SQLite sessions/transactions are thread-local. A serial coroutine dispatcher such as
 * IO.limitedParallelism(1) can still change threads after suspension, stranding the transaction.
 * Keep creation, imports, suspending progress callbacks, commit/rollback and close on one thread.
 */
internal suspend fun <T> onOfflineDatabaseThread(block: suspend () -> T): T =
    Executors.newSingleThreadExecutor { task -> Thread(task, "OfflinePackDatabase").apply { isDaemon = true } }
        .asCoroutineDispatcher().use { databaseThread -> withContext(databaseThread) { block() } }
