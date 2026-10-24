package hu.app.bmenetrend

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import java.util.Locale

class BMenetrendApplication : Application() {
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
