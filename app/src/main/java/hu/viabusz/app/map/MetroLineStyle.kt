package hu.viabusz.app.map

import hu.viabusz.app.R
import java.util.Locale

/** Metro line colors and the numbered panel from the supplied emblems. */
object MetroLineStyle {
    data class Badge(val drawable: Int, val number: String, val color: Int)
    fun render(context: android.content.Context, badge: Badge, height: Int) = NumberCircleRenderer.render(context, badge.drawable, height)
    fun forLine(number: String?, mode: String?): Badge? {
        if (mode != "SUBWAY") return null
        return when (number?.trim()?.uppercase(Locale.ROOT)?.removePrefix("M")) {
            "1" -> Badge(R.drawable.metro_m1, "1", 0xFFFFD800.toInt())
            "2" -> Badge(R.drawable.metro_m2, "2", 0xFFE41B13.toInt())
            "3" -> Badge(R.drawable.metro_m3, "3", 0xFF075DA4.toInt())
            "4" -> Badge(R.drawable.metro_m4, "4", 0xFF46A43B.toInt())
            else -> null
        }
    }
}
