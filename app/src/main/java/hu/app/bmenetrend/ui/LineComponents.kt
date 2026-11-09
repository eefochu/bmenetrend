package hu.app.bmenetrend.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import hu.app.bmenetrend.map.HevLineStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.app.bmenetrend.R
import hu.app.bmenetrend.data.TransitRoute
import hu.app.bmenetrend.data.TransitStop
import hu.app.bmenetrend.data.Vehicle
import hu.app.bmenetrend.map.VehicleMarkerStyle
import hu.app.bmenetrend.map.MetroLineStyle

private val railG = Regex("^G\\d.*")
private val railS = Regex("^S\\d.*")
private val railZ = Regex("^Z\\d.*")

fun railLineColor(number: String): Color {
    val clean = number.trim().uppercase(java.util.Locale.ROOT)
    return when {
        clean.startsWith("IR") -> Color(0xFF008001) // InterRegio: #008001
        clean.matches(railG) || clean == "G" -> Color(0xFFA9CD45) // G-s: #A9CD45
        clean.matches(railS) -> Color(0xFF00AFEF) // sXX: #00AFEF
        clean.matches(railZ) || clean == "Z" -> Color(0xFFFECD28) // Z-s: #FECD28
        else -> Color(0xFF2E5EA7) // EC, EN, Ex, IC, S, Sz, Gy, rjx, etc.: #2E5EA7
    }
}

/** Preserve service colours while keeping small route labels readable. */
fun badgeTextColor(background: Color): Color {
    val light=background.luminance()
    if(1.05f/(light+.05f)>=4.5f) return Color.White
    val ink=Color(0xFF171B20)
    return if((light+.05f)/(ink.luminance()+.05f)>=4.5f) ink else Color.Black
}
fun railLineTextColor(number: String): Color = badgeTextColor(railLineColor(number))

fun lineColor(provider: String, mode: String?, source: String? = null, number: String? = null): Color {
    if (number != null && (provider in setOf("mav", "gysev") || mode in setOf("RAIL", "TRAMTRAIN"))) {
        return railLineColor(number)
    }
    if (mode in setOf("SUBWAY", "SUBURBAN_RAILWAY") && source?.matches(Regex("[a-fA-F0-9]{6}")) == true) return Color((0xFF000000L or source.toLong(16)).toInt())
    return when (mode) {
        "TRAM" -> Color(0xFFFFD800)
        "TROLLEYBUS" -> Color(0xFFE31B23)
        "SUBWAY", "RAIL", "SUBURBAN_RAILWAY", "TRAMTRAIN" -> if (number != null && mode != "SUBWAY") railLineColor(number) else Color(0xFF0066AA)
        else -> when (provider) {
            "volan" -> Color(0xFFF9B900)
            "viabusz" -> Color(0xFF205C48)
            "kanyargo" -> Color(0xFF6750A4)
            "homm" -> Color(0xFFBF5700)
            else -> Color(0xFF199DCF)
        }
    }
}
fun lineColor(route: TransitRoute) = lineColor(route.providerId, route.transportMode, route.color, route.number)

@Composable
fun LineBadge(number: String, provider: String, mode: String? = null, sourceColor: String? = null, showIcon: Boolean = true,
              /** When set, every badge (bus, tram, train, metro, HÉV) occupies exactly this size so grids line up. */
              uniform: androidx.compose.ui.unit.DpSize? = null) {
    val metro = MetroLineStyle.forLine(number, mode)
    val hev = HevLineStyle.forLine(number, mode)
    val logo = metro?.let { TransitLineLogo.metro(it.number) } ?: hev?.let { TransitLineLogo.hev(it.number) }
    if (logo != null) {
        val context = LocalContext.current
        val emblemHeight = uniform?.height ?: 36.dp
        val heightPx = with(LocalDensity.current) { emblemHeight.roundToPx() }
        val emblem = remember(logo, heightPx) { TransitLineLogo.render(context, logo, heightPx).asImageBitmap() }
        val description = if (metro != null) stringResource(R.string.metro_line_badge, metro.number)
            else stringResource(R.string.hev_line_badge, requireNotNull(hev).number)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(emblem, description,
                if (uniform != null) Modifier.size(uniform) else Modifier.width(76.dp).height(36.dp),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit)
            if (hev != null && hev.number.length > 2) Text(hev.number.substring(2), Modifier.padding(start = 4.dp),
                style = MaterialTheme.typography.labelLarge, fontFamily = OpenSans)
        }
        return
    }
    val isRail = provider in setOf("mav", "gysev") || mode in setOf("RAIL", "TRAMTRAIN") || (mode == "SUBURBAN_RAILWAY" && number.matches(Regex("^[GSZgsz]\\d.*")))
    val color = if (isRail) railLineColor(number) else lineColor(provider, mode, sourceColor, number)
    val textColor = if (isRail) railLineTextColor(number) else badgeTextColor(color)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showIcon) Image(rememberTransitIcon(VehicleMarkerStyle.forTransport(provider, mode).drawableRes, 28.dp),
            stringResource(when (mode) { "TRAM" -> R.string.emma_mode_tram; "TROLLEYBUS" -> R.string.emma_mode_trolley;
                "SUBWAY" -> R.string.emma_mode_metro; "RAIL", "SUBURBAN_RAILWAY", "TRAMTRAIN" -> R.string.emma_mode_train; else -> R.string.emma_mode_bus }), Modifier.size(28.dp))
        Surface(shape = RoundedCornerShape(4.dp), color = color) {
            Box(
                (if (uniform != null) Modifier.size(uniform).padding(horizontal = 5.dp)
                else Modifier.heightIn(min = 28.dp).widthIn(min = 44.dp).padding(horizontal = 8.dp, vertical = 2.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = number,
                    color = textColor,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.3).sp,
                        lineHeight = 18.sp,
                    ),
                    fontFamily = OpenSans,
                    maxLines = 1,
                    // Fixed-size badges shrink long labels ("S968", "922B") instead of growing wider than the rest.
                    autoSize = if (uniform != null) androidx.compose.foundation.text.TextAutoSize.StepBased(9.sp, 15.sp, 0.5.sp) else null,
                )
            }
        }
    }
}
@Composable
fun LineBadge(route: TransitRoute, showIcon: Boolean = true, uniform: androidx.compose.ui.unit.DpSize? = null) =
    LineBadge(route.number, route.providerId, route.transportMode, route.color, showIcon, uniform)

/** The one size every line badge takes in grids and one-line strips. */
internal val UniformBadge = androidx.compose.ui.unit.DpSize(52.dp, 28.dp)

@Composable
fun LineStopRow(
    stop: TransitStop,
    first: Boolean,
    last: Boolean,
    color: Color,
    onClick: () -> Unit,
    past: Boolean = false,
    highlighted: Boolean = false,
    vehicleIcon: Int? = null,
    vehicleBitmap: ImageBitmap? = null,
    arrivalTime: String? = null,
    scheduledTime: String? = null,
    isLive: Boolean = false,
    delayMinutes: Int = 0,
) {
    val rail = if (past) color.copy(alpha = .45f) else color
    val surface = MaterialTheme.colorScheme.surface
    Surface(color = if (highlighted) color.copy(alpha = .10f) else Color.Transparent) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(onClick = onClick).padding(start = 12.dp, end = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(48.dp).fillMaxHeight().heightIn(min = 76.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.matchParentSize()) {
                    val x = size.width / 2
                    val y = size.height / 2
                    drawLine(rail, Offset(x, if (first) y else 0f), Offset(x, if (last) y else size.height), 5.dp.toPx())
                    drawCircle(surface, 10.dp.toPx(), Offset(x, y))
                    drawCircle(rail, if (first || last) 8.dp.toPx() else 6.dp.toPx(), Offset(x, y))
                    if (first || last) drawCircle(surface, 3.dp.toPx(), Offset(x, y))
                }
                if (vehicleBitmap != null) Image(vehicleBitmap, stringResource(R.string.line_vehicle_here), Modifier.width(44.dp).height(30.dp))
                else vehicleIcon?.let { Image(rememberTransitIcon(it, 30.dp), stringResource(R.string.line_vehicle_here), Modifier.size(30.dp)) }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp, top = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val resolvedPlatform = stop.platformCode?.ifBlank { null }
                    ?: hu.app.bmenetrend.data.BkkPlatformIndex.getPlatformCode(stop.id)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stop.name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (past) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false))
                    resolvedPlatform?.let { PlatformBadge(it) }
                }
                val subtitle = listOfNotNull(if (resolvedPlatform == null) stop.platformCode?.let { stringResource(R.string.emma_platform, it) } else null,
                    if (first) stringResource(R.string.ui_origin) else if (last) stringResource(R.string.ui_terminus) else null).joinToString(" · ")
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (arrivalTime != null) {
                Column(
                    modifier = Modifier.widthIn(max = 132.dp).padding(start = 8.dp, top = 16.dp, bottom = 16.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (isLive) {
                            Icon(
                                Icons.Rounded.Sensors,
                                contentDescription = stringResource(R.string.ui_live_arrival),
                                modifier = Modifier.size(13.dp),
                                tint = if (past) MaterialTheme.colorScheme.onSurfaceVariant else if (delayMinutes > 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = if (isLive && scheduledTime != null) stringResource(R.string.board_estimated_time,arrivalTime) else arrivalTime,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.SemiBold,
                            color = when {
                                past -> MaterialTheme.colorScheme.onSurfaceVariant
                                isLive && delayMinutes > 2 -> MaterialTheme.colorScheme.error
                                isLive -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                    if (isLive && scheduledTime != null) {
                        Text(
                            text = stringResource(R.string.board_original_time,scheduledTime),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LineVehicleBetween(color: Color, icon: Int, bitmap: ImageBitmap? = null) {
    Row(Modifier.fillMaxWidth().height(58.dp).padding(start = 12.dp, end = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(48.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize()) { drawLine(color, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 5.dp.toPx()) }
            if (bitmap != null) Image(bitmap, stringResource(R.string.line_vehicle_here), Modifier.width(44.dp).height(30.dp))
            else Image(rememberTransitIcon(icon, 30.dp), stringResource(R.string.line_vehicle_here), Modifier.size(30.dp))
        }
        Text(stringResource(R.string.line_on_the_way), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Estimate status is a separate text sibling; the normal operator badge keeps its colour. */
@Composable
fun VehicleServiceBadge(vehicle: Vehicle, number: String? = vehicle.displayRouteNumber, estimated: Boolean = vehicle.isRouteEstimated) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ServiceBadge(number ?: "?", vehicle.providerId, vehicle.transportMode, vehicle.markerColor, vehicle.trainNumber)
        if (estimated) Text(stringResource(R.string.vehicle_route_estimated),
            style = MaterialTheme.typography.labelMedium, maxLines = 1,
            color = if (MaterialTheme.colorScheme.surface.luminance() < .5f) Color(0xFFFFD54F) else Color(0xFF795600))
    }
}

/** Operator artwork shared by map-related controls, search and local planning options. */
@Composable
internal fun TransitVehicleIcon(provider: String, mode: String? = "BUS", size: Dp = 36.dp) {
    Image(rememberTransitIcon(VehicleMarkerStyle.forTransport(provider, mode).drawableRes, size),
        null, Modifier.size(size))
}
