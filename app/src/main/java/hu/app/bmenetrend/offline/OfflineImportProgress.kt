package hu.app.bmenetrend.offline

import androidx.compose.runtime.Immutable

enum class OfflineImportStep {
    WAITING, CALENDARS, STOPS, ROUTES, TRIPS, STOP_TIMES, TRANSFERS,
    LOCAL_SOURCES, LOCAL_TIMETABLES, VALIDATING, CATALOGUE, INDEXING, OPTIMIZING, SAVING, VERIFYING, ACTIVATING,
}

@Immutable
data class OfflineImportProgress(
    val step: OfflineImportStep,
    val records: Long = 0,
    val completed: Long = 0,
    val total: Long = 0,
) {
    val fraction: Float? get() = if (total > 0) (completed.toDouble() / total).toFloat().coerceIn(0f, 1f) else null
}

internal val OfflineImportStep.labelResource: Int get() = when (this) {
    OfflineImportStep.WAITING -> hu.app.bmenetrend.R.string.offline_step_waiting
    OfflineImportStep.CALENDARS -> hu.app.bmenetrend.R.string.offline_step_calendars
    OfflineImportStep.STOPS -> hu.app.bmenetrend.R.string.offline_step_stops
    OfflineImportStep.ROUTES -> hu.app.bmenetrend.R.string.offline_step_routes
    OfflineImportStep.TRIPS -> hu.app.bmenetrend.R.string.offline_step_trips
    OfflineImportStep.STOP_TIMES -> hu.app.bmenetrend.R.string.offline_step_stop_times
    OfflineImportStep.TRANSFERS -> hu.app.bmenetrend.R.string.offline_step_transfers
    OfflineImportStep.LOCAL_SOURCES -> hu.app.bmenetrend.R.string.offline_step_local_sources
    OfflineImportStep.LOCAL_TIMETABLES -> hu.app.bmenetrend.R.string.offline_step_local
    OfflineImportStep.VALIDATING -> hu.app.bmenetrend.R.string.offline_step_validating
    OfflineImportStep.CATALOGUE -> hu.app.bmenetrend.R.string.offline_step_catalogue
    OfflineImportStep.INDEXING -> hu.app.bmenetrend.R.string.offline_step_indexing
    OfflineImportStep.OPTIMIZING -> hu.app.bmenetrend.R.string.offline_step_optimizing
    OfflineImportStep.SAVING -> hu.app.bmenetrend.R.string.offline_step_saving
    OfflineImportStep.VERIFYING -> hu.app.bmenetrend.R.string.offline_step_verifying
    OfflineImportStep.ACTIVATING -> hu.app.bmenetrend.R.string.offline_step_activating
}
