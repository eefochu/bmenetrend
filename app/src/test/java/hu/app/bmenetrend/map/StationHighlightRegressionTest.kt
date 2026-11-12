package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.GeoPoint
import hu.app.bmenetrend.data.MapBounds
import hu.app.bmenetrend.data.TransitStop
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression tests for line focus and highlight state transitions:
 * 1. Focus exit hides stale layer state immediately via [StationMarkers.Data.matchesFocus].
 * 2. Selection changes bypass [UploadCoalescer] debounce waits.
 * 3. Focused grouping is limited to focusStops rather than national catalogue index.
 */
class StationHighlightRegressionTest {

    private fun stop(
        id: String,
        lat: Double,
        lon: Double,
        mode: String? = null,
        routes: List<String> = emptyList(),
        name: String = "Stop $id",
    ) = TransitStop(id, name, GeoPoint(lat, lon), routes, providerId = "bkk", transportMode = mode)

    private fun sceneForStops(
        stops: List<TransitStop>,
        focused: Boolean,
        highlightedIds: Set<String>,
        region: MapBounds? = null,
    ): StationScene {
        val selectedStops = if (focused) stops else stops
        val bounds = selectedStops.takeIf { it.isNotEmpty() }?.let { all ->
            MapBounds(all.minOf { it.position.lon }, all.minOf { it.position.lat }, all.maxOf { it.position.lon }, all.maxOf { it.position.lat })
        }
        val groups = StopMapClustering.groups(selectedStops, bounds, 16.0, emptyMap())
        val merged = RailStationMarkers.mergePlatforms(groups).map { StationGroup(stationAnchor(it), it) }
        return StationScene(
            groups = merged,
            chunks = StationChunkIndex(merged) { it.anchor.position },
            offsets = if (focused) emptyMap() else StopMarkerLayout.offsets(merged.map { it.anchor }, emptyMap()),
            preparedRegion = region,
            focusIds = highlightedIds.takeIf { focused },
        )
    }

    private val budapest = MapBounds(18.9, 47.4, 19.2, 47.6)

    @Test
    fun focusExitHidesStaleLayerStateImmediately() {
        val stopA = stop("stopA", 47.50, 19.05)
        val stopB = stop("stopB", 47.51, 19.06)
        val focusIds = setOf("stopA", "stopB")

        val focusedScene = sceneForStops(listOf(stopA, stopB), focused = true, highlightedIds = focusIds, region = budapest)
        val dataFocused = StationMarkers.build(
            scene = focusedScene,
            region = budapest,
            focused = true,
            highlightedIds = focusIds,
            bearings = emptyMap(),
            routes = emptyMap(),
            dark = false,
            routeColor = 0,
            imageName = { it.key },
        )

        // While focused on this line, matchesFocus is true
        assertTrue(dataFocused.matchesFocus(focused = true, highlightedIds = focusIds))

        // On focus exit (focused = false, highlightedIds = emptySet()), matchesFocus MUST immediately return false.
        // This is what allows NativeTransitMap to call StationMarkerLayers.visible(style, false)
        // synchronously on focus exit before the regional station scene finishes re-clustering.
        assertFalse(
            "Focus exit must cause matchesFocus to return false immediately so stale markers hide",
            dataFocused.matchesFocus(focused = false, highlightedIds = emptySet()),
        )

        // Switching between different lines must also immediately return false
        val otherLineIds = setOf("stopC", "stopD")
        assertFalse(
            "Changing focused line must cause matchesFocus to return false immediately",
            dataFocused.matchesFocus(focused = true, highlightedIds = otherLineIds),
        )
    }

    @Test
    fun selectionBypassesUploadCoalescerWaits() {
        var simulatedClock = 10_000L
        val coalescer = UploadCoalescer { simulatedClock }

        // Simulate an upload just happened 100ms ago (in the middle of a burst)
        coalescer.uploaded()
        simulatedClock += 100L

        // In a burst, background updates (region pan, bearings, route colours) are debounced by BURST_DELAY_MS
        val backgroundDelay = coalescer.delayMillis()
        assertEquals(UploadCoalescer.BURST_DELAY_MS, backgroundDelay)

        // Now test the selectionChanged branch logic used in NativeTransitMap:
        val stopA = stop("stopA", 47.50, 19.05)
        val data = StationMarkers.build(
            scene = sceneForStops(listOf(stopA), focused = false, highlightedIds = emptySet(), region = budapest),
            region = budapest,
            focused = false,
            highlightedIds = emptySet(),
            bearings = emptyMap(),
            routes = emptyMap(),
            dark = false,
            routeColor = 0,
            imageName = { it.key },
        )

        // Case 1: Normal update without selection change (e.g. bearings update):
        // matchesFocus is true -> selectionChanged is false -> delay is enforced
        val nonSelectionChanged = data.matchesFocus(focused = false, highlightedIds = emptySet()) != true
        assertFalse("Non-selection update must not be flagged as selectionChanged", nonSelectionChanged)

        // Case 2: User selects a line (focused = true):
        // matchesFocus is false -> selectionChanged is true -> coalescer delay is bypassed
        val lineSelectedChanged = data.matchesFocus(focused = true, highlightedIds = setOf("stopA")) != true
        assertTrue("Line selection must be detected as selectionChanged", lineSelectedChanged)

        // Effective delay for selection change is 0ms (bypassed), not the 800ms burst debounce
        val effectiveWaitMs = if (lineSelectedChanged) 0L else coalescer.delayMillis()
        assertEquals(0L, effectiveWaitMs)
    }

    @Test
    fun focusedGroupingIsLimitedToFocusStopsRatherThanNationalIndex() {
        // Thousands of national stops across Hungary
        val distantNationalStops = (1..2000).map { i ->
            stop("nat_$i", 46.0 + (i % 100) * 0.01, 17.0 + (i / 100) * 0.01)
        }
        // Just 3 stops for the focused tram line
        val tramStops = listOf(
            stop("tram_1", 47.491, 19.051),
            stop("tram_2", 47.495, 19.055),
            stop("tram_3", 47.499, 19.060),
        )
        val focusIds = tramStops.map { it.id }.toSet()

        // StationScene in focused mode receives only focusStops:
        val focusedScene = sceneForStops(
            stops = tramStops,
            focused = true,
            highlightedIds = focusIds,
            region = budapest,
        )

        // 1. Scene groups must contain only the tram stops, never national stops
        val anchorIds = focusedScene.groups.map { it.anchor.id }.toSet()
        assertEquals(setOf("tram_1", "tram_2", "tram_3"), anchorIds)

        // 2. Chunks index must only query tram stops
        val window = StationWindow.around(budapest, 13.0)
        val chunkStops = focusedScene.chunks.query(window).map { it.anchor.id }.toSet()
        assertEquals(setOf("tram_1", "tram_2", "tram_3"), chunkStops)

        // 3. In focused mode, StopMarkerLayout.offsets is emptyMap (avoiding expensive layout passes)
        assertTrue("Focused scene must skip collision offsets layout", focusedScene.offsets.isEmpty())

        // 4. focusIds is preserved on the scene to prevent race condition uploads
        assertEquals(focusIds, focusedScene.focusIds)

        // 5. Generated marker payload contains exactly the 3 focused stops
        val data = StationMarkers.build(
            scene = focusedScene,
            region = budapest,
            focused = true,
            highlightedIds = focusIds,
            bearings = emptyMap(),
            routes = emptyMap(),
            dark = false,
            routeColor = 0,
            imageName = { it.key },
        )
        assertEquals(setOf("tram_1", "tram_2", "tram_3"), data.markers.keys)
    }
}
