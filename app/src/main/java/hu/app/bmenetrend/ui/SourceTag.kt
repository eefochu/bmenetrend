package hu.app.bmenetrend.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import hu.app.bmenetrend.R
import hu.app.bmenetrend.data.DataSource
import hu.app.bmenetrend.data.Vehicle
import java.time.Duration
import java.time.Instant

/** The feed label for a set of sources, or null when nothing is known (local/offline data). */
@Composable
internal fun sourceLabel(sources: Set<DataSource>): String? = when {
    sources.isEmpty() -> null
    sources.size > 1 -> stringResource(R.string.source_mixed)
    sources.first() == DataSource.BKK -> stringResource(R.string.source_bkk)
    else -> stringResource(R.string.source_emma)
}

/**
 * A small, quiet label naming the feed that actually served a result (BKK API or the EMMA fallback),
 * plus its age when the time it was received is known and it is no longer fresh.
 */
@Composable
internal fun SourceTag(sources: Set<DataSource>, modifier: Modifier = Modifier, fetchedAt: Instant? = null, now: Instant? = null) {
    val label = sourceLabel(sources) ?: return
    SourceTagText(withAge(label, fetchedAt, now), modifier)
}

/** Vehicle variant: a merged train is scoped honestly (position from BKK, trip details from EMMA). */
@Composable
internal fun VehicleSourceTag(vehicle: Vehicle, modifier: Modifier = Modifier) {
    val position = vehicle.positionSource
    val details = vehicle.detailsSource
    val label = when {
        position == DataSource.BKK && details == DataSource.EMMA -> stringResource(R.string.source_bkk_position_emma_details)
        else -> sourceLabel(setOfNotNull(position ?: details)) ?: return
    }
    SourceTagText(label, modifier)
}

@Composable
private fun withAge(label: String, fetchedAt: Instant?, now: Instant?): String {
    if (fetchedAt == null || now == null) return label
    val seconds = Duration.between(fetchedAt, now).seconds
    return when {
        seconds < 15 -> label
        seconds < 120 -> stringResource(R.string.source_age_seconds, label, seconds.toInt())
        else -> stringResource(R.string.source_age_minutes, label, (seconds / 60).toInt())
    }
}

@Composable
private fun SourceTagText(text: String, modifier: Modifier) {
    val description = stringResource(R.string.source_description, text)
    Surface(modifier.clearAndSetSemantics { contentDescription = description }, shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurfaceVariant) {
        Text(text, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}
