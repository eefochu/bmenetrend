package hu.app.bmenetrend.map

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.ceil

class GoogleMarkerPlacementTest {

    @Test
    fun zeroOffset_placesMarkerAtExactCenter() {
        val iconSide = 48
        val pixelsPerDp = 2.0f
        val offset = StopMarkerLayout.Offset(0.0, 0.0)

        val placement = GoogleMarkerPlacement.create(iconSide, offset, pixelsPerDp)

        val expectedPadding = ceil(StopMarkerLayout.MAX_OFFSET_DP * pixelsPerDp).toInt()
        assertEquals(expectedPadding, placement.padding)
        assertEquals(iconSide + expectedPadding * 2, placement.side)
        assertEquals(0.5f, placement.anchorU, 0.0001f)
        assertEquals(0.5f, placement.anchorV, 0.0001f)
    }

    @Test
    fun positiveAndNegativeShiftFormula_shiftsAnchorInOppositeFractionalDirection() {
        val iconSide = 32
        val pixelsPerDp = 1.5f
        val padding = ceil(StopMarkerLayout.MAX_OFFSET_DP * pixelsPerDp).toInt()
        val side = iconSide + padding * 2

        // Positive shift (+x right, +y down in screen space)
        val positiveOffset = StopMarkerLayout.Offset(10.0, 15.0)
        val posPlacement = GoogleMarkerPlacement.create(iconSide, positiveOffset, pixelsPerDp)

        val expectedPosU = (0.5 - 10.0 * pixelsPerDp / side).toFloat()
        val expectedPosV = (0.5 - 15.0 * pixelsPerDp / side).toFloat()
        assertEquals(expectedPosU, posPlacement.anchorU, 0.0001f)
        assertEquals(expectedPosV, posPlacement.anchorV, 0.0001f)
        assertTrue("Positive offset x must decrease anchorU below 0.5", posPlacement.anchorU < 0.5f)
        assertTrue("Positive offset y must decrease anchorV below 0.5", posPlacement.anchorV < 0.5f)

        // Negative shift (-x left, -y up in screen space)
        val negativeOffset = StopMarkerLayout.Offset(-8.0, -12.0)
        val negPlacement = GoogleMarkerPlacement.create(iconSide, negativeOffset, pixelsPerDp)

        val expectedNegU = (0.5 - (-8.0) * pixelsPerDp / side).toFloat()
        val expectedNegV = (0.5 - (-12.0) * pixelsPerDp / side).toFloat()
        assertEquals(expectedNegU, negPlacement.anchorU, 0.0001f)
        assertEquals(expectedNegV, negPlacement.anchorV, 0.0001f)
        assertTrue("Negative offset x must increase anchorU above 0.5", negPlacement.anchorU > 0.5f)
        assertTrue("Negative offset y must increase anchorV above 0.5", negPlacement.anchorV > 0.5f)
    }

    @Test
    fun maxOffsetsStayStrictlyWithinAnchorsAndPaddedBounds() {
        val maxOffset = StopMarkerLayout.MAX_OFFSET_DP // 24.0 dp
        val testDensities = listOf(1.0f, 1.5f, 2.0f, 2.75f, 3.0f, 3.5f, 4.0f)
        val testIconSides = listOf(1, 16, 24, 36, 48, 72)

        val extremeOffsets = listOf(
            StopMarkerLayout.Offset(maxOffset, 0.0),
            StopMarkerLayout.Offset(-maxOffset, 0.0),
            StopMarkerLayout.Offset(0.0, maxOffset),
            StopMarkerLayout.Offset(0.0, -maxOffset),
            StopMarkerLayout.Offset(maxOffset, maxOffset),
            StopMarkerLayout.Offset(-maxOffset, -maxOffset),
            StopMarkerLayout.Offset(maxOffset, -maxOffset),
            StopMarkerLayout.Offset(-maxOffset, maxOffset),
        )

        for (density in testDensities) {
            for (iconSide in testIconSides) {
                for (offset in extremeOffsets) {
                    val placement = GoogleMarkerPlacement.create(iconSide, offset, density)

                    // Anchors must be strictly in [0, 1] as required by Google Maps Android SDK
                    assertTrue(
                        "anchorU must be >= 0.0f, got ${placement.anchorU} (density=$density, iconSide=$iconSide, offset=$offset)",
                        placement.anchorU >= 0.0f,
                    )
                    assertTrue(
                        "anchorU must be <= 1.0f, got ${placement.anchorU} (density=$density, iconSide=$iconSide, offset=$offset)",
                        placement.anchorU <= 1.0f,
                    )
                    assertTrue(
                        "anchorV must be >= 0.0f, got ${placement.anchorV} (density=$density, iconSide=$iconSide, offset=$offset)",
                        placement.anchorV >= 0.0f,
                    )
                    assertTrue(
                        "anchorV must be <= 1.0f, got ${placement.anchorV} (density=$density, iconSide=$iconSide, offset=$offset)",
                        placement.anchorV <= 1.0f,
                    )

                    // Padding must be large enough so the shifted icon does not clip
                    val maxShiftPx = maxOffset * density
                    assertTrue(
                        "Padding must cover maximum shift in pixels: padding=${placement.padding} >= maxShift=$maxShiftPx",
                        placement.padding >= maxShiftPx,
                    )

                    // Total bitmap side must be iconSide + 2 * padding
                    assertEquals(iconSide + placement.padding * 2, placement.side)
                }
            }
        }
    }

    @Test
    fun densityAndScaleCorrectness() {
        val iconSide = 22
        val baseDensity = 2.5f
        val scales = listOf(0.75f, 1.0f, 1.25f, 1.5f)
        val offset = StopMarkerLayout.Offset(12.0, -6.0)

        for (scale in scales) {
            val pixelsPerDp = baseDensity * scale
            val placement = GoogleMarkerPlacement.create(iconSide, offset, pixelsPerDp)

            val expectedPadding = ceil(StopMarkerLayout.MAX_OFFSET_DP * pixelsPerDp).toInt()
            val expectedSide = iconSide + expectedPadding * 2
            val expectedAnchorU = (0.5 - offset.x * pixelsPerDp / expectedSide).toFloat()
            val expectedAnchorV = (0.5 - offset.y * pixelsPerDp / expectedSide).toFloat()

            assertEquals("Padding matches scaled pixelsPerDp", expectedPadding, placement.padding)
            assertEquals("Side matches iconSide + 2 * padding", expectedSide, placement.side)
            assertEquals("AnchorU matches scaled shift", expectedAnchorU, placement.anchorU, 0.0001f)
            assertEquals("AnchorV matches scaled shift", expectedAnchorV, placement.anchorV, 0.0001f)
        }
    }

    @Test
    fun zeroPixelsPerDp_givesVehicleCenterAndNoPadding() {
        val iconSide = 36
        // Non-zero offset input should have no effect when pixelsPerDp is zero
        val offsets = listOf(
            StopMarkerLayout.Offset(0.0, 0.0),
            StopMarkerLayout.Offset(10.0, 10.0),
            StopMarkerLayout.Offset(-24.0, 24.0),
        )

        for (offset in offsets) {
            val placement = GoogleMarkerPlacement.create(iconSide, offset, pixelsPerDp = 0f)

            assertEquals("Zero pixelsPerDp must produce zero padding for vehicles", 0, placement.padding)
            assertEquals("Side must equal unpadded iconSide", iconSide, placement.side)
            assertEquals("AnchorU must be exactly centered for vehicles", 0.5f, placement.anchorU, 0.0f)
            assertEquals("AnchorV must be exactly centered for vehicles", 0.5f, placement.anchorV, 0.0f)
        }
    }
}
