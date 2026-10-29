package hu.app.bmenetrend

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import java.util.Locale
import java.io.File
import android.os.Build
import android.app.ActivityManager
import android.app.ApplicationExitInfo

class BMenetrendApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        hu.app.bmenetrend.offline.NetworkAccessPolicy.initialize(this)
        hu.app.bmenetrend.offline.NetworkAccessPolicy.monitorConnectivity(this)
        val reports = CrashReports(File(filesDir, "last-crash.txt"))
        val details = "${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE}), Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}"
        // Android 11 also exposes native crashes and low-memory kills, which never reach a Java handler.
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            val prefs = getSharedPreferences("crash_reports", 0)
            val exit = getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(packageName, 0, 10)
                .firstOrNull { it.reason in setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
                    ApplicationExitInfo.REASON_ANR) || it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE &&
                    it.reason in setOf(ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_SIGNALED) }
            if (exit != null && exit.timestamp > prefs.getLong("last_exit", 0) &&
                System.currentTimeMillis() - exit.timestamp in 0..172_800_000L) {
                if (reports.read() == null) reports.recordProcessExit(
                    "$details\nTime: ${java.time.Instant.ofEpochMilli(exit.timestamp)}\nReason: ${exit.reason}\nDescription: ${exit.description}\nStatus: ${exit.status}\nMemory: PSS ${exit.pss} KiB, RSS ${exit.rss} KiB")
                prefs.edit().putLong("last_exit", exit.timestamp).apply()
            }
        }
        reports.install(details)
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base.withHungarianLocale())
    }
}

internal fun Context.withHungarianLocale(): Context {
    val configuration = Configuration(resources.configuration).apply {
        setLocale(Locale.forLanguageTag("hu-HU"))
    }
    return createConfigurationContext(configuration)
}
