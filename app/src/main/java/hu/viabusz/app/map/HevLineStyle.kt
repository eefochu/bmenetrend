package hu.viabusz.app.map

import android.content.Context
import android.graphics.*
import hu.viabusz.app.R
import hu.viabusz.app.data.HevSupport

object HevLineStyle {
    data class Badge(val drawable: Int, val number: String, val panel: Int = 0) {
        val color: Int get() = Color.parseColor("#${HevSupport.color(number)}")
    }
    fun forLine(number: String?, mode: String?): Badge? {
        if (mode != "SUBURBAN_RAILWAY") return null
        val name = HevSupport.number(number, null) ?: return null
        return when (name[1]) {
            '5' -> Badge(R.drawable.hev_h5, name)
            '6' -> Badge(R.drawable.hev_h6, name)
            '7' -> Badge(R.drawable.hev_h7, name)
            '8' -> Badge(R.drawable.hev_h8_h9, name, 1)
            '9' -> Badge(R.drawable.hev_h8_h9, name, 2)
            else -> null
        }
    }

    /** Display only the supplied numbered circle, including the separate H8/H9 panels. */
    fun render(context: Context, badge: Badge, height: Int): Bitmap =
        NumberCircleRenderer.render(context, badge.drawable, height, badge.panel)
}
