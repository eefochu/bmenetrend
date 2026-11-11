package hu.app.bmenetrend

import android.content.Context
import android.content.SharedPreferences
import hu.app.bmenetrend.data.OtaRepository
import hu.app.bmenetrend.data.OtaUpdateInfo
import hu.app.bmenetrend.data.UpdateChannel
import hu.app.bmenetrend.data.VersionComparator
import hu.app.bmenetrend.offline.NetworkAccessPolicy
import hu.app.bmenetrend.ui.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant

/** In-app update checks, APK download and install; owns the `ota*` fields of [UiState]. */
internal class OtaUpdates(
    private val repository: OtaRepository,
    private val preferences: SharedPreferences,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<UiState>,
) {
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var downloadedUrl: String? = null

    fun setChannel(channel: UpdateChannel) {
        downloadJob?.cancel()
        downloadedUrl = null
        preferences.edit().putString("ota_channel", channel.key).apply()
        state.update { it.copy(otaChannel = channel, otaUpdateAvailable = null, otaCheckError = null,
            otaDownloadedApk = null, otaDownloadProgress = null, showOtaDialog = false) }
        check(isAutoCheck = false)
    }

    fun check(isAutoCheck: Boolean) {
        if (NetworkAccessPolicy.offline.value) return
        val channel = state.value.otaChannel
        if (!isAutoCheck) state.update { it.copy(otaCheckLoading = true, otaCheckError = null) }
        checkJob?.cancel()
        checkJob = scope.launch {
            try {
                val info = repository.fetchUpdateInfo(channel)
                val isNew = info != null && VersionComparator.isNewer(info.version, BuildConfig.VERSION_NAME)
                if (info?.apkUrl != downloadedUrl) downloadedUrl = null
                state.update { current ->
                    current.copy(
                        otaCheckLoading = false,
                        otaCheckError = null,
                        otaLastChecked = Instant.now(),
                        otaUpdateAvailable = if (isNew) info else null,
                        otaDownloadedApk = current.otaDownloadedApk.takeIf { downloadedUrl != null && downloadedUrl == info?.apkUrl },
                        showOtaDialog = isNew,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                state.update { it.copy(otaCheckLoading = false,
                    otaCheckError = if (isAutoCheck) it.otaCheckError else e.localizedMessage ?: "Hiba a frissítések lekérésekor") }
            }
        }
    }

    fun downloadAndInstall(context: Context, info: OtaUpdateInfo) {
        if (NetworkAccessPolicy.offline.value) return
        val apkUrl = info.apkUrl ?: return
        if (downloadJob?.isActive == true) return
        val downloaded = state.value.otaDownloadedApk
        if (downloadedUrl == apkUrl && downloaded != null && downloaded.exists()) {
            try {
                repository.installApk(downloaded)
                return
            } catch (_: Exception) {}
        }
        state.update { it.copy(otaDownloadProgress = 0f, otaDownloadedApk = null, otaCheckError = null) }
        downloadJob = scope.launch {
            try {
                val target = File(File(context.cacheDir, "ota_updates"), "bmenetrend-${info.version}-${info.tag}.apk")
                val file = repository.downloadApk(apkUrl, target) { progress ->
                    state.update { it.copy(otaDownloadProgress = progress) }
                }
                downloadedUrl = apkUrl
                state.update { it.copy(otaDownloadProgress = null, otaDownloadedApk = file) }
                repository.installApk(file)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                state.update { it.copy(otaDownloadProgress = null, otaCheckError = e.localizedMessage ?: "Letöltési hiba") }
            }
        }
    }

    fun dismissDialog() = state.update { it.copy(showOtaDialog = false) }

    /** Offline mode stops every update request; the caller resets the visible OTA state. */
    fun cancel() { checkJob?.cancel(); downloadJob?.cancel() }
}
