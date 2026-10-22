package hu.viabusz.app.data

import android.content.Context
import hu.viabusz.app.R
import kotlinx.coroutines.CancellationException
import java.time.Instant

class EmmaRepository(context: Context) {
    val routes = EmmaParser.catalogue(context.assets.open("volan-routes.json").bufferedReader().use { it.readText() })
    val catalogue = routes.map { TransitRoute("volan:${it.sourceId}", it.number, it.name, emptyList(), "volan", it.url) }
    private val feed = EmmaFeedClient()
    data class Result(val vehicles: List<Vehicle>, val messageResource: Int, val fetchedAt: Instant)

    suspend fun refresh(force: Boolean = false): Result = try {
        val result = feed.get(force)
        val message = when {
            result.stale -> R.string.volan_stale
            result.vehicles.isEmpty() -> R.string.volan_no_positions
            else -> R.plurals.volan_positions
        }
        Result(result.vehicles, message, result.fetchedAt)
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) { Result(emptyList(), R.string.volan_unavailable, Instant.now()) }
}
