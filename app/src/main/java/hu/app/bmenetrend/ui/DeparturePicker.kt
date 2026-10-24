package hu.app.bmenetrend.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hu.app.bmenetrend.R
import hu.app.bmenetrend.data.TripPlanner
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun departureLabel(departure: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale).withZone(TripPlanner.zone).format(departure)
}

@Composable
internal fun DepartureControl(departure: Instant?, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.Schedule, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.planner_departure), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (departure == null) stringResource(R.string.planner_leave_now) else departureLabel(departure),
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            }
            Icon(Icons.Rounded.ExpandMore, null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeparturePicker(departure: Instant?, onDismiss: () -> Unit, onConfirm: (Instant?) -> Unit) {
    val initial = remember { (departure ?: Instant.now().plusSeconds(300)).atZone(TripPlanner.zone) }
    var later by rememberSaveable { mutableStateOf(departure != null) }
    var dateEpoch by rememberSaveable { mutableLongStateOf(initial.toLocalDate().toEpochDay()) }
    var hour by rememberSaveable { mutableIntStateOf(initial.hour) }
    var minute by rememberSaveable { mutableIntStateOf(initial.minute) }
    var dateOpen by rememberSaveable { mutableStateOf(false) }
    var timeOpen by rememberSaveable { mutableStateOf(false) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    val date = LocalDate.ofEpochDay(dateEpoch)
    val locale = LocalConfiguration.current.locales[0]
    AlertDialog(onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Schedule, null) },
        title = { Text(stringResource(R.string.planner_departure)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(false, true).forEach { option ->
                    Row(Modifier.fillMaxWidth().selectable(selected = later == option, role = Role.RadioButton,
                        onClick = { later = option; invalid = false }).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = later == option, onClick = null)
                        Text(stringResource(if (option) R.string.planner_leave_later else R.string.planner_leave_now), Modifier.padding(start = 12.dp))
                    }
                }
                if (later) {
                    OutlinedButton(onClick = { dateOpen = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Rounded.CalendarMonth, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
                        Text(date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)))
                    }
                    OutlinedButton(onClick = { timeOpen = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Rounded.Schedule, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
                        Text(LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern("HH:mm")))
                    }
                    Text(stringResource(R.string.planner_departure_zone), style = MaterialTheme.typography.bodySmall)
                }
                if (invalid) Text(stringResource(R.string.planner_departure_invalid), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = {
            if (!later) onConfirm(null)
            else {
                val chosen = TripPlanner.departure(date, LocalTime.of(hour, minute), Instant.now())
                if (chosen == null) invalid = true else onConfirm(chosen)
            }
        }) { Text(stringResource(R.string.planner_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.planner_cancel)) } })
    if (dateOpen) {
        val today = remember { LocalDate.now(TripPlanner.zone) }
        // Material's picker uses midnight UTC to identify a calendar date, not an instant in Budapest.
        val picker = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = remember(today) { object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate() >= today
                override fun isSelectableYear(year: Int) = year >= today.year
            } })
        DatePickerDialog(onDismissRequest = { dateOpen = false },
            confirmButton = { TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                picker.selectedDateMillis?.let { dateEpoch = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay() }
                invalid = false; dateOpen = false
            }) { Text(stringResource(R.string.planner_apply)) } },
            dismissButton = { TextButton(onClick = { dateOpen = false }) { Text(stringResource(R.string.planner_cancel)) } }) {
            DatePicker(picker, modifier = Modifier.verticalScroll(rememberScrollState()))
        }
    }
    if (timeOpen) {
        val picker = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
        AlertDialog(onDismissRequest = { timeOpen = false }, title = { Text(stringResource(R.string.planner_departure_time)) },
            text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimeInput(picker) } },
            confirmButton = { TextButton(onClick = { hour = picker.hour; minute = picker.minute; invalid = false; timeOpen = false }) {
                Text(stringResource(R.string.planner_apply))
            } }, dismissButton = { TextButton(onClick = { timeOpen = false }) { Text(stringResource(R.string.planner_cancel)) } })
    }
}
