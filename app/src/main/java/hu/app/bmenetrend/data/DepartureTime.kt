package hu.app.bmenetrend.data

import java.time.Duration
import java.time.Instant

object DepartureTime {
    val windows = listOf(30, 60, 120, 180, 240, 300)
    fun minutes(now: Instant, departure: Instant) = kotlin.math.ceil(Duration.between(now, departure).toMillis() / 60_000.0).toInt().coerceAtLeast(0)
    fun compact(minutes: Int, hungarian: Boolean): String {
        val value = minutes.coerceAtLeast(0)
        val hours = value / 60
        val remaining = value % 60
        val h = if (hungarian) "ó" else "h"
        val m = if (hungarian) "p" else "min"
        return if (hours == 0) "$remaining$m" else if (remaining == 0) "$hours$h" else "$hours$h $remaining$m"
    }
}
