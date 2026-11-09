package hu.app.bmenetrend.data

import kotlinx.coroutines.CancellationException

/** A validated downloaded timetable can remain useful during a live service failure. */
internal suspend fun planWithRecovery(online: suspend () -> TripPlan, saved: suspend () -> TripPlan): TripPlan {
    try {
        return online()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        val fallback = try { saved() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw failure }
        // An absent/expired pack, unmatched endpoints or an empty offline search
        // must not turn an unavailable live service into a false "no route" answer.
        if (fallback.itineraries.isEmpty()) throw failure
        return fallback.copy(partial = true)
    }
}
