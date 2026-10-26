package hu.app.bmenetrend.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class LineAppearanceTest {
    @Test fun lineColoursFollowTransportTypeAndOperatorRatherThanPersonalAccent() {
        assertEquals(Color(0xFFFFD800), lineColor("bkk", "TRAM", "000000"))
        assertEquals(Color(0xFF199DCF), lineColor("bkk", "BUS", "FF0000"))
        assertEquals(Color(0xFFE31B23), lineColor("bkk", "TROLLEYBUS"))
        assertEquals(Color(0xFFF9B900), lineColor("volan", "COACH"))
        assertEquals(Color(0xFF205C48), lineColor("viabusz", null))
    }
    @Test fun metroUsesValidatedIndividualLineColour() {
        assertEquals(Color(0xFF008D5E), lineColor("bkk", "SUBWAY", "008D5E"))
        assertEquals(Color(0xFF0066AA), lineColor("bkk", "SUBWAY", "invalid"))
    }
    @Test fun smallTrainLabelsHaveReadableContrastWithoutChangingServiceColours() {
        listOf("IR","G10","S10","Z30","Gy","IC","EC").forEach { number ->
            val bg=railLineColor(number).luminance();val fg=railLineTextColor(number).luminance()
            assertTrue(number,(maxOf(bg,fg)+.05f)/(minOf(bg,fg)+.05f)>=4.5f)
        }
    }

}
