package hu.app.bmenetrend.map

import kotlin.math.ceil

/** Transparent margins let a shared icon shift around its real coordinate using a valid SDK anchor. */
internal data class GoogleMarkerPlacement(
    val padding: Int,
    val side: Int,
    val anchorU: Float,
    val anchorV: Float,
) {
    companion object {
        fun create(iconSide: Int, offset: StopMarkerLayout.Offset, pixelsPerDp: Float): GoogleMarkerPlacement {
            val padding = ceil(StopMarkerLayout.MAX_OFFSET_DP * pixelsPerDp).toInt()
            val side = iconSide + padding * 2
            return GoogleMarkerPlacement(padding, side,
                (.5 - offset.x * pixelsPerDp / side).toFloat(),
                (.5 - offset.y * pixelsPerDp / side).toFloat())
        }
    }
}
