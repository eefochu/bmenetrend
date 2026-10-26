package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.TransitStop
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt

object StopMarkerColors {
    fun palette(stops: List<TransitStop>): List<Int> = stops.flatMap { stop ->
        stop.providerIds.map { StopMarkerLayout.color(it,stop.transportMode) }
    }.distinct().sorted()

    /** A closed, eased cycle: no jump when the last operator fades back to the first. */
    fun at(colors: List<Int>,phase: Double): Int {
        require(colors.isNotEmpty())
        if(colors.size==1) return colors.first()
        val position=(phase-floor(phase))*colors.size
        val index=floor(position).toInt()
        val weight=(1-cos((position-index)*PI))/2
        val a=colors[index]; val b=colors[(index+1)%colors.size]
        fun channel(shift: Int)=(((a ushr shift) and 255)*(1-weight)+((b ushr shift) and 255)*weight).roundToInt()
        return (255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
