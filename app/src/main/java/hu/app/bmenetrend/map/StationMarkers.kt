package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.MapBounds
import hu.app.bmenetrend.data.RailSupport
import hu.app.bmenetrend.data.TransitRoute
import hu.app.bmenetrend.data.TransitStop
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

internal data class StationGroup(val anchor: TransitStop, val members: List<TransitStop>)

internal class StationScene(
    val groups: List<StationGroup>,
    val chunks: StationChunkIndex<StationGroup>,
    val offsets: Map<String, StopMarkerLayout.Offset>,
)

internal fun stationAnchor(members: List<TransitStop>): TransitStop = members.first().let { first ->
    first.copy(position = (members.firstOrNull { it.providerId == "volan" } ?: first).position)
}

/**
 * Station markers are uploaded as one GeoJSON source: every rail station in the country, plus every
 * other stop inside a large region around the camera (see [nextRegion]). MapLibre culls, tiles and
 * draws on its own threads, so panning within the region never rebuilds, re-serializes or re-uploads
 * markers. Zoom visibility, direction arrows and the highlighted stop are style layers and filters.
 *
 * The region bound matters: the whole national catalogue in one source cost ~560 MB of native heap
 * in MapLibre's tiler (measured on the emulator), far too much for low-RAM phones.
 */
internal object StationMarkers {
    const val SOURCE = "station-markers"
    const val RAIL_LAYER = "station-symbols-rail"
    /** Kept as the historical id: route and vehicle layers are positioned relative to it. */
    const val ROAD_LAYER = "station-symbols"
    const val SELECTED_LAYER = "station-selected"
    val ARROW_LAYERS = listOf("station-arrows-0", "station-arrows-1")
    const val RAIL_MIN_ZOOM = 9f
    const val ROAD_MIN_ZOOM = 12f
    val ARROW_MIN_ZOOM = StopMapClustering.PLATFORM_ZOOM.toFloat()
    /**
     * Deepest level the source is tiled to; closer zooms reuse these tiles. geojson-vt keeps a copy
     * of every point and its properties per generated tile and never evicts them, so tiling the whole
     * country down to MapLibre's default 18 cost hundreds of MB of native heap. Points need no detail
     * beyond the level where arrows appear.
     */
    val SOURCE_MAX_ZOOM = StopMapClustering.PLATFORM_ZOOM.toInt()
    /** Road stops appear at 12; load their region one zoom level earlier. */
    const val REGION_MIN_ZOOM = 11.0
    /** Minimum region span (≈35 × 33 km at Budapest's latitude: the whole city at once). */
    const val REGION_MIN_LON_SPAN = 0.45
    const val REGION_MIN_LAT_SPAN = 0.3
    const val REGION_VIEWPORT_FACTOR = 4.0
    /** Rebuild only once the viewport gets within this share of the region's edge. */
    const val REGION_EDGE_SHARE = 0.15

    /**
     * The region whose road stops are loaded, or [current] unchanged. It changes only when the
     * viewport (at a zoom where road stops matter) nears the region's edge, so ordinary panning and
     * zooming never trigger a rebuild. Below [REGION_MIN_ZOOM] the region is kept as it is.
     */
    fun nextRegion(current: MapBounds?, viewport: MapBounds, zoom: Double): MapBounds? {
        if (zoom < REGION_MIN_ZOOM) return current
        if (current != null) {
            val insetLon = (current.east - current.west) * REGION_EDGE_SHARE
            val insetLat = (current.north - current.south) * REGION_EDGE_SHARE
            if (viewport.west >= current.west + insetLon && viewport.east <= current.east - insetLon &&
                viewport.south >= current.south + insetLat && viewport.north <= current.north - insetLat) return current
        }
        val lonSpan = maxOf((viewport.east - viewport.west) * REGION_VIEWPORT_FACTOR, REGION_MIN_LON_SPAN)
        val latSpan = maxOf((viewport.north - viewport.south) * REGION_VIEWPORT_FACTOR, REGION_MIN_LAT_SPAN)
        val lon = (viewport.west + viewport.east) / 2
        val lat = (viewport.south + viewport.north) / 2
        return MapBounds(lon - lonSpan / 2, lat - latSpan / 2, lon + lonSpan / 2, lat + latSpan / 2)
    }

    private fun MapBounds.contains(stop: TransitStop) =
        stop.position.lon in west..east && stop.position.lat in south..north

    class Marker(
        val group: StationGroup,
        /** The member that is the anchor's own record (taps select it), as before. */
        val stop: TransitStop,
        val palette: List<Int>,
        val offset: StopMarkerLayout.Offset,
        val rail: Boolean,
        val arrows: List<Int>,
    ) {
        val id: String get() = group.anchor.id
    }

    class Data(
        json: String,
        /** Image name → dot sprite (no arrows, not selected) for every marker in [json]. */
        val sprites: Map<String, StationSprite>,
        val markers: Map<String, Marker>,
        /** Member stop id → anchor id, for the highlighted stop and shared-stop taps. */
        val anchorByStop: Map<String, String>,
        val index: StationChunkIndex<Marker>,
        val focused: Boolean,
        val dark: Boolean,
        val routeColor: Int,
        private val scene: StationScene,
        private val region: MapBounds?,
        private val highlightedIds: Set<String>,
        private val bearings: Map<String, List<Double>>,
        private val routes: Map<String, TransitRoute>,
        /** Every route id a marker's colours can depend on. */
        private val referencedRoutes: Set<String>,
    ) {
        /** The GeoJSON payload until it is uploaded; released afterwards (it can be megabytes). */
        var json: String? = json
            private set
        /** Identifies the payload after [json] is released, to skip identical re-uploads. */
        val payloadKey: Long = (json.length.toLong() shl 32) or (json.hashCode().toLong() and 0xffffffffL)
        fun releaseJson() { json = null }

        /**
         * True when rebuilding would produce the same markers: only the route catalogue changed, and
         * not in any field a referenced route's marker colour is derived from. Live vehicle updates
         * replace the catalogue every few seconds; this keeps them from re-serializing the country.
         */
        fun matches(scene: StationScene, region: MapBounds?, focused: Boolean, highlightedIds: Set<String>, bearings: Map<String, List<Double>>,
                    routes: Map<String, TransitRoute>, dark: Boolean, routeColor: Int): Boolean =
            scene === this.scene && region == this.region && focused == this.focused && dark == this.dark && routeColor == this.routeColor &&
                (bearings === this.bearings || bearings == this.bearings) && highlightedIds == this.highlightedIds &&
                (routes === this.routes || referencedRoutes.all { sameColour(this.routes[it], routes[it]) })

        fun spriteFor(marker: Marker, selected: Boolean) = StationSprite(marker.palette, StopMarkerLayout.DIAMETER_DP.toInt(),
            focused, selected, dark, routeColor)
    }

    /** The inputs StopMarkerColors derives a route's marker colour from. */
    fun sameColour(a: TransitRoute?, b: TransitRoute?): Boolean = a === b || a != null && b != null &&
        a.number == b.number && a.name == b.name && a.transportMode == b.transportMode && a.providerId == b.providerId && a.color == b.color

    /** Same arrow rule as the per-window renderer: ≤2 distinct headings, 5° buckets. */
    fun directions(members: List<TransitStop>, bearings: Map<String, List<Double>>): List<Int> =
        members.flatMap { bearings[it.id].orEmpty() }
            .fold(mutableListOf<Double>()) { angles, value ->
                if (angles.none { StopDirections.difference(it, value) < 35 }) angles.add(value)
                angles
            }.takeIf { it.size <= 2 }.orEmpty().map { ((round(it / 5) * 5).toInt() + 360) % 360 }.distinct().sorted()

    fun build(
        scene: StationScene,
        region: MapBounds?,
        focused: Boolean,
        highlightedIds: Set<String>,
        bearings: Map<String, List<Double>>,
        routes: Map<String, TransitRoute>,
        dark: Boolean,
        routeColor: Int,
        imageName: (StationSprite) -> String,
        ensureActive: () -> Unit = {},
    ): Data {
        val groups = if (focused) scene.groups.filter { group -> group.members.any { it.id in highlightedIds } }
            else scene.groups.filter { group -> RailSupport.isTrain(group.anchor.transportMode) || region?.contains(group.anchor) == true }
        val markers = LinkedHashMap<String, Marker>(groups.size * 2)
        val anchorByStop = HashMap<String, String>(groups.size * 2)
        val sprites = HashMap<String, StationSprite>()
        val referencedRoutes = HashSet<String>()
        val json = StringBuilder(groups.size * 160 + 64)
        json.append("{\"type\":\"FeatureCollection\",\"features\":[")
        var first = true
        groups.forEachIndexed { index, group ->
            if (index and 511 == 0) ensureActive()
            val anchor = group.anchor
            val lon = anchor.position.lon; val lat = anchor.position.lat
            if (!lon.isFinite() || !lat.isFinite()) return@forEachIndexed
            group.members.forEach { referencedRoutes.addAll(it.routeIds) }
            val palette = StopMarkerColors.palette(group.members, routes)
            val offset = if (focused) StopMarkerLayout.Offset() else scene.offsets[anchor.id] ?: StopMarkerLayout.Offset()
            val marker = Marker(group, group.members.firstOrNull { it.id == anchor.id } ?: anchor, palette, offset,
                RailSupport.isTrain(anchor.transportMode), directions(group.members, bearings))
            markers[anchor.id] = marker
            group.members.forEach { anchorByStop.putIfAbsent(it.id, anchor.id) }
            val sprite = StationSprite(palette, StopMarkerLayout.DIAMETER_DP.toInt(), focused, false, dark, routeColor)
            val image = imageName(sprite)
            sprites[image] = sprite
            if (!first) json.append(',')
            first = false
            json.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
                .append(lon).append(',').append(lat).append("]},\"properties\":{\"id\":")
            appendString(json, anchor.id)
            json.append(",\"icon\":"); appendString(json, image)
            json.append(",\"rail\":").append(marker.rail)
            json.append(",\"offset\":["); appendNumber(json, offset.x); json.append(','); appendNumber(json, offset.y); json.append(']')
            marker.arrows.forEachIndexed { arrow, heading ->
                // icon-offset is applied in the icon's rotated frame: pre-rotate it back so the
                // arrow sits around the displaced dot exactly as in the baked sprite.
                val angle = Math.toRadians(heading.toDouble())
                val x = offset.x * cos(angle) + offset.y * sin(angle)
                val y = -offset.x * sin(angle) + offset.y * cos(angle)
                json.append(",\"a").append(arrow).append("\":").append(heading)
                json.append(",\"o").append(arrow).append("\":["); appendNumber(json, x); json.append(','); appendNumber(json, y); json.append(']')
            }
            json.append("}}")
        }
        json.append("]}")
        return Data(json.toString(), sprites, markers, anchorByStop,
            StationChunkIndex(markers.values.toList()) { it.group.anchor.position }, focused, dark, routeColor,
            scene, region, highlightedIds, bearings, routes, referencedRoutes)
    }

    private fun appendNumber(out: StringBuilder, value: Double) {
        out.append(if (value.isFinite()) value else 0.0)
    }

    private fun appendString(out: StringBuilder, value: String) = out.appendJsonString(value)
}

/** Appends [value] as a JSON string literal. Map sources are written directly, without a Gson tree. */
internal fun StringBuilder.appendJsonString(value: String): StringBuilder {
    append('"')
    for (char in value) when {
        char == '"' -> append("\\\"")
        char == '\\' -> append("\\\\")
        char < ' ' -> append("\\u").append(String.format("%04x", char.code))
        else -> append(char)
    }
    return append('"')
}
