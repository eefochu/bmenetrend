package hu.viabusz.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hu.viabusz.app.R
import hu.viabusz.app.data.DepartureTime

@Composable
internal fun compactDuration(minutes: Int): String = DepartureTime.compact(minutes, LocalConfiguration.current.locales[0].language == "hu")

@Composable
internal fun countdown(minutes: Int): String = if (minutes <= 0) stringResource(R.string.ui_now) else compactDuration(minutes)

@Composable
internal fun ServiceBadge(number: String, provider: String, mode: String?, color: String?, trainNumber: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LineBadge(number, provider, mode, color)
        if (trainNumber != null && trainNumber != number) Text(stringResource(R.string.rail_train_number, trainNumber),
            style = MaterialTheme.typography.labelMedium, fontFamily = OpenSans, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
