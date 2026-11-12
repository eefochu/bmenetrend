package hu.app.bmenetrend.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.runtime.withFrameNanos
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.*
import hu.app.bmenetrend.data.*
import kotlin.math.roundToInt

/** Reconciles markers by identity; live updates move them instead of clearing the map. */
internal class GoogleMapRenderer(private val map: GoogleMap, private val density: Float) {
    private class Entry(var spec: GoogleMarkerSpec) {
        var marker: Marker? = null
        var iconKey = ""
        var visible = false
        var point = spec.point
        var zIndex = Float.NaN
        var title: String? = null
        var anchorU = .5f
        var anchorV = .5f
        var arrowMarker: Marker? = null
        var arrowVisible = false
        var arrowKey = ""
        var arrowPoint = spec.point
        var heading = Float.NaN
        var arrowZ = Float.NaN
        var overview = false
    }
    private val entries = LinkedHashMap<String, Entry>()
    private val bitmaps = LinkedHashMap<String, Bitmap>()
    private val descriptors = LinkedHashMap<String, BitmapDescriptor>()
    private val offsets = LinkedHashMap<String, StopMarkerLayout.Offset>()
    private val routeLines = mutableListOf<Polyline>()
    private val trackLines = mutableListOf<Polyline>()
    private val pins = mutableMapOf<String, Marker>()
    private var focus: LineMapFocus? = null
    private var sceneFocusKey: String? = null
    private var zoom = 13.0

    val cachedIcons: Set<String> get() = bitmaps.keys.toSet()
    val cachedOffsets: Map<String, StopMarkerLayout.Offset> get() = offsets.toMap()
    fun target(marker: Marker): GoogleMarkerSpec? = entries[marker.tag as? String]?.spec

    fun focus(focus: LineMapFocus?, zoom: Double) {
        this.focus = focus
        this.zoom = zoom
        // Missing markers are created by the batched scene upload, not by an input callback.
        entries.values.forEach { apply(it, create = false) }
    }

    /** [budget] is the number of marker changes allowed per frame; it is re-read every frame. */
    suspend fun sync(scene: GoogleScene, images: MutableMap<String, Bitmap>, focus: LineMapFocus?, zoom: Double,
                     budget: () -> Int = { IDLE_BUDGET }) {
        images.forEach { (key, value) -> bitmaps.put(key, value)?.takeIf { it !== value }?.recycle() }
        images.clear() // renderer owns these even if reconciliation is cancelled between frames
        scene.offsets.forEach { (id, offset) -> offsets.remove(id); offsets[id] = offset }
        while (offsets.size > 8192) offsets.remove(offsets.keys.first())
        this.focus = focus
        this.zoom = zoom
        sceneFocusKey = scene.focusKey
        val ids = scene.markers.mapTo(HashSet()) { it.id }
        var mutations = 0
        for (id in entries.keys.filter { it !in ids }) {
            entries.remove(id)?.let { entry ->
                entry.marker?.let { it.remove(); mutations++ }
                entry.arrowMarker?.let { it.remove(); mutations++ }
            }
            if (mutations >= budget()) { withFrameNanos { }; mutations = 0 }
        }
        for (spec in scene.markers) {
            val entry = entries.getOrPut(spec.id) { Entry(spec) }
            entry.spec = spec
            if (apply(entry, create = true)) mutations += if (spec.arrow != null) 2 else 1
            if (mutations >= budget()) { withFrameNanos { }; mutations = 0 }
        }
        val used = scene.markers.flatMapTo(HashSet()) { listOfNotNull(it.icon, it.dot, it.arrow) }
        if (bitmaps.size > used.size + 64) {
            bitmaps.keys.filter { it !in used }.forEach { bitmaps.remove(it)?.recycle() }
            descriptors.clear()
        }
        if (descriptors.size > 512) descriptors.clear()
    }

    private fun apply(entry: Entry, create: Boolean): Boolean {
        val spec = entry.spec
        val vehicle = spec.vehicle
        val visible = if (vehicle != null) {
            focus?.includes(vehicle) != false && TransitMapVisibility.vehicle(vehicle.transportMode, zoom, focus != null)
        } else sceneFocusKey == focus?.key && TransitMapVisibility.stop(spec.stops.firstOrNull()?.transportMode, zoom, focus != null)
        if (!visible) {
            var changed = hideArrow(entry)
            if (entry.visible) { entry.marker?.isVisible = false; entry.visible = false; changed = true }
            return changed
        }
        // Only trains are visible below the vehicle zoom: they keep their icon, drawn smaller.
        val overview = vehicle != null && focus == null && zoom < TransitMapVisibility.VEHICLE_SYMBOL_MIN_ZOOM
        entry.overview = overview
        val rawKey = spec.icon
        val bitmap = bitmaps[rawKey] ?: return false
        if (entry.marker == null && !create) return false
        val pixels = if (vehicle != null) {
            VehicleMarkerLayout.diameterDp(spec.selected, overview) * density
        } else bitmap.width / 3f * density * spec.scale
        val side = pixels.roundToInt().coerceAtLeast(1)
        val placement = GoogleMarkerPlacement.create(side, spec.offset, if (vehicle == null) density * spec.scale else 0f)
        val key = "$rawKey@$side+${placement.padding}"
        val descriptor = descriptors.getOrPut(key) {
            val scaled = if (placement.padding == 0) Bitmap.createScaledBitmap(bitmap, side, side, true) else {
                Bitmap.createBitmap(placement.side, placement.side, Bitmap.Config.ARGB_8888).also { padded ->
                    padded.density = bitmap.density
                    val start = placement.padding
                    Canvas(padded).drawBitmap(bitmap, null, Rect(start, start, start + side, start + side),
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                }
            }
            try { BitmapDescriptorFactory.fromBitmap(scaled) } finally { if (scaled !== bitmap) scaled.recycle() }
        }
        val z = if (vehicle != null) (if (spec.selected) 40f else 30f) else if (spec.selected) 20f else 10f
        var changed = false
        // Set the custom icon at creation so the SDK never stages its default red pin.
        val marker = entry.marker ?: map.addMarker(MarkerOptions()
            .position(spec.point.latLng()).anchor(placement.anchorU, placement.anchorV).icon(descriptor)
            .flat(vehicle == null)
            .title(spec.title).zIndex(z).visible(false))?.also {
            it.tag = spec.id; entry.marker = it; entry.point = spec.point
            entry.iconKey = key; entry.title = spec.title; entry.zIndex = z; changed = true
            entry.anchorU = placement.anchorU; entry.anchorV = placement.anchorV
        } ?: return false
        if (entry.point != spec.point) {
            marker.position = spec.point.latLng(); entry.point = spec.point; changed = true
        }
        if (entry.title != spec.title) {
            marker.title = spec.title; entry.title = spec.title; changed = true
        }
        if (entry.iconKey != key) {
            marker.setIcon(descriptor); entry.iconKey = key; changed = true
        }
        if (entry.anchorU != placement.anchorU || entry.anchorV != placement.anchorV) {
            marker.setAnchor(placement.anchorU, placement.anchorV)
            entry.anchorU = placement.anchorU; entry.anchorV = placement.anchorV; changed = true
        }
        if (entry.zIndex != z) { marker.zIndex = z; entry.zIndex = z; changed = true }
        if (!entry.visible) { marker.isVisible = true; entry.visible = true; changed = true }
        return applyArrow(entry, create) || changed
    }

    private fun hideArrow(entry: Entry): Boolean {
        if (!entry.arrowVisible) return false
        entry.arrowMarker?.isVisible = false; entry.arrowVisible = false
        return true
    }

    private fun applyArrow(entry: Entry, create: Boolean): Boolean {
        val spec = entry.spec
        val rawKey = spec.arrow ?: return hideArrow(entry)
        val heading = spec.heading ?: return hideArrow(entry)
        val bitmap = bitmaps[rawKey] ?: return false
        if (entry.arrowMarker == null && !create) return false
        val side = (VehicleMarkerLayout.ARROW_CANVAS_DP * density *
            VehicleMarkerLayout.arrowScale(spec.selected, entry.overview)).roundToInt().coerceAtLeast(1)
        val key = "$rawKey@$side+0"
        val descriptor = descriptors.getOrPut(key) {
            val scaled = Bitmap.createScaledBitmap(bitmap, side, side, true)
            try { BitmapDescriptorFactory.fromBitmap(scaled) } finally { if (scaled !== bitmap) scaled.recycle() }
        }
        val z = if (spec.selected) 39f else 29f
        var changed = false
        val marker = entry.arrowMarker ?: map.addMarker(MarkerOptions()
            .position(spec.point.latLng()).anchor(.5f, .5f).icon(descriptor).flat(true)
            .rotation(heading).zIndex(z).visible(false))?.also {
            it.tag = spec.id; entry.arrowMarker = it; entry.arrowPoint = spec.point
            entry.arrowKey = key; entry.heading = heading; entry.arrowZ = z; changed = true
        } ?: return false
        if (entry.arrowPoint != spec.point) {
            marker.position = spec.point.latLng(); entry.arrowPoint = spec.point; changed = true
        }
        if (entry.arrowKey != key) { marker.setIcon(descriptor); entry.arrowKey = key; changed = true }
        if (entry.heading != heading) { marker.rotation = heading; entry.heading = heading; changed = true }
        if (entry.arrowZ != z) { marker.zIndex = z; entry.arrowZ = z; changed = true }
        if (!entry.arrowVisible) { marker.isVisible = true; entry.arrowVisible = true; changed = true }
        return changed
    }

    /** Visible markers near a screen point, positioned as drawn, for [MapTapResolver]. */
    fun tapTargets(x: Float, y: Float): Pair<List<MapTapResolver.VehicleTarget>, List<StopHitTesting.Target>> {
        val projection = map.projection
        val angle = Math.toRadians(map.cameraPosition.bearing.toDouble())
        val cos = kotlin.math.cos(angle); val sin = kotlin.math.sin(angle)
        val reach = 80 * density
        val vehicles = ArrayList<MapTapResolver.VehicleTarget>()
        val stops = ArrayList<StopHitTesting.Target>()
        for (entry in entries.values) {
            if (!entry.visible || entry.marker == null) continue
            val spec = entry.spec
            val point = projection.toScreenLocation(spec.point.latLng())
            if (kotlin.math.abs(point.x - x) > reach || kotlin.math.abs(point.y - y) > reach) continue
            val vehicle = spec.vehicle
            if (vehicle != null) {
                vehicles += MapTapResolver.VehicleTarget(vehicle.id, point.x.toDouble(), point.y.toDouble(),
                    VehicleMarkerLayout.diameterDp(spec.selected, entry.overview) * density / 2.0)
            } else {
                val stop = spec.stops.firstOrNull() ?: continue
                val scale = density * spec.scale
                val dx = spec.offset.x * scale; val dy = spec.offset.y * scale
                stops += StopHitTesting.Target(stop, point.x + dx * cos + dy * sin, point.y - dx * sin + dy * cos,
                    StopMarkerLayout.DIAMETER_DP * scale / 2)
            }
        }
        return vehicles to stops
    }

    fun spec(stopId: String): GoogleMarkerSpec? = entries.values.firstOrNull { it.spec.vehicle == null && it.spec.stops.firstOrNull()?.id == stopId }?.spec

    fun route(paths: List<List<GeoPoint>>, color: Int, dark: Boolean, dotted: Boolean) {
        routeLines.forEach(Polyline::remove); routeLines.clear()
        for (path in paths.filter { it.size >= 2 }) {
            val points = path.map { it.latLng() }
            for ((width, lineColor) in listOf(8f to if (dark) Color.rgb(23, 33, 45) else Color.WHITE, 5f to color)) {
                val options = PolylineOptions().addAll(points).color(lineColor).width(width * density)
                    .startCap(RoundCap()).endCap(RoundCap()).jointType(JointType.ROUND).zIndex(2f)
                if (dotted) options.pattern(listOf(Dash(8f * density), Gap(8f * density)))
                map.addPolyline(options).let(routeLines::add)
            }
        }
    }

    fun tracks(lines: List<Pair<List<GeoPoint>, Int>>) {
        trackLines.forEach(Polyline::remove); trackLines.clear()
        lines.forEach { (path, color) ->
            trackLines += map.addPolyline(PolylineOptions().addAll(path.map { it.latLng() }).color(color)
                .width(4f * density).zIndex(1f))
        }
    }

    fun pin(id: String, point: GeoPoint?, color: Int, title: String? = null) {
        if (point == null) { pins.remove(id)?.remove(); return }
        val marker = pins[id] ?: run {
            val key = "pin:$color"
            val icon = descriptors.getOrPut(key) {
                val size = (20 * density).roundToInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.color = Color.WHITE; canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
                paint.color = color; canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2 * density, paint)
                try { BitmapDescriptorFactory.fromBitmap(bitmap) } finally { bitmap.recycle() }
            }
            map.addMarker(MarkerOptions().position(point.latLng()).anchor(.5f, .5f).icon(icon).zIndex(50f))
                ?.also { pins[id] = it }
        }
        marker?.position = point.latLng()
        marker?.title = title
    }

    companion object {
        const val IDLE_BUDGET = 48
        /** Small enough that creating markers under a moving finger does not drop frames. */
        const val GESTURE_BUDGET = 12
    }

    private var userDot: Marker? = null
    private var userCone: Marker? = null
    private var userPoint: GeoPoint? = null
    private var userBearing: Float? = null

    /** The position dot and its facing cone; moved in place on every frame of a glide. */
    fun userPosition(point: GeoPoint?) {
        userPoint = point
        if (point == null) {
            userDot?.remove(); userDot = null; userCone?.remove(); userCone = null
            return
        }
        val dot = userDot ?: map.addMarker(MarkerOptions().position(point.latLng()).anchor(.5f, .5f)
            .icon(userIcon("user-dot") { UserLocationMarker.renderDot(density, 0) }).zIndex(61f))?.also { userDot = it }
        dot?.position = point.latLng()
        userHeading(userBearing)
    }

    fun userHeading(degrees: Float?) {
        userBearing = degrees
        val point = userPoint
        if (point == null || degrees == null) { userCone?.remove(); userCone = null; return }
        val cone = userCone ?: map.addMarker(MarkerOptions().position(point.latLng()).anchor(.5f, .5f).flat(true)
            .icon(userIcon("user-cone") { UserLocationMarker.renderCone(density, 0) }).zIndex(60f))?.also { userCone = it }
        cone?.position = point.latLng()
        cone?.rotation = degrees
    }

    private fun userIcon(key: String, render: () -> Bitmap): BitmapDescriptor = descriptors.getOrPut(key) {
        val bitmap = render()
        try { BitmapDescriptorFactory.fromBitmap(bitmap) } finally { bitmap.recycle() }
    }

    fun clear() {
        entries.values.forEach { it.marker?.remove(); it.arrowMarker?.remove() }; entries.clear()
        routeLines.forEach(Polyline::remove); trackLines.forEach(Polyline::remove)
        pins.values.forEach(Marker::remove); pins.clear()
        userDot?.remove(); userDot = null; userCone?.remove(); userCone = null
        bitmaps.values.forEach(Bitmap::recycle); bitmaps.clear(); descriptors.clear()
        offsets.clear()
    }
}

internal fun GeoPoint.latLng() = LatLng(lat, lon)
