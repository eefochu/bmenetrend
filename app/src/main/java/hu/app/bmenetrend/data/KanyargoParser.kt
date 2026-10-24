package hu.app.bmenetrend.data

import org.jsoup.Jsoup
import java.security.MessageDigest
import java.time.Instant

/** Versioned official PDF imports, reviewed against the rendered timetable.
 * The website has no public trip API. We validate both source PDF fingerprints
 * before enabling today's imported timetable. Changed sources fail closed while
 * stops, route browsing and fresh vehicle GPS remain available.
 */
object KanyargoParser {
    const val SCHOOL_PATH = "documents/menetrend-KanyarGO-2026-szept-1-tol.pdf"
    const val BREAK_PATH = "documents/menetrend-KanyarGO-2026-aug-10-tanszuneti.pdf"
    const val SCHOOL_SHA = "866231b9e84d16f16cde34b11adc67682cc049a360ed7f41ae430e7e7f1cdd66"
    const val BREAK_SHA = "60f7e5db621e456fb63e747fe8ca204e0fd58b744d75ba2897ba3aaae6330e98"
    val routeNumbers = setOf("1A", "1D", "1J", "1M", "1MT", "1MV", "1T", "1V")

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun validateNetwork(base: TransitNetwork, timetableHtml: String, schoolSha: String, breakSha: String, now: Instant): TransitNetwork {
        require(base.routes.all { it.providerId == "kanyargo" } && base.routes.map { it.number }.toSet() == routeNumbers)
        require(schoolSha == SCHOOL_SHA && breakSha == BREAK_SHA) { "KanyarGO timetable document changed" }
        val document = Jsoup.parse(timetableHtml, "https://kanyargo.paty.hu/")
        val today = now.atZone(ViabuszParser.zone).toLocalDate()
        val calendar = KanyargoCalendar.activeCalendar(today)
        val activeDocuments = document.select(".tmtbl-cnt a[href]").map { it.attr("href").substringBefore('?') }
            .filter { it.endsWith(".pdf", ignoreCase = true) }
        val knownDocuments = setOf("https://kanyargo.paty.hu/$SCHOOL_PATH", "https://kanyargo.paty.hu/$BREAK_PATH")
        // The present static page keeps the school link during holidays. A switch to
        // the already-verified break document is also valid; any new PDF needs review.
        require(activeDocuments.isNotEmpty() && activeDocuments.all { it in knownDocuments } &&
            (calendar != "school_term" || activeDocuments.any { it.endsWith(SCHOOL_PATH) })) {
            "KanyarGO active timetable changed"
        }
        // The operator sometimes publishes only the PDF links, without its optional
        // HTML stop board. PDF source identity is independent of that presentation.
        val date = today.takeIf { calendar != null }
        return base.copy(fetchedAt = now, serviceDate = date, providerServiceDates = mapOf("kanyargo" to date), warning = null)
    }

    fun parseVehicles(body: String, now: Instant): List<Vehicle> = ViabuszParser.parseVehicles(body, now).map {
        it.copy(id = "kanyargo:${it.id}", providerId = "kanyargo")
    }

    fun parseAggregateArrivals(body: String): List<LiveArrival> = ViabuszParser.parseAggregateArrivals(body).map {
        it.copy(stopId = "kanyargo:${it.stopId}")
    }

    fun parseVehicleArrivals(body: String, vehicles: List<Vehicle>): List<LiveArrival> =
        ViabuszParser.parseVehicleArrivals(body, vehicles.map { it.copy(id = it.id.removePrefix("kanyargo:")) }).map {
            it.copy(stopId = "kanyargo:${it.stopId}", vehicleId = it.vehicleId?.let { id -> "kanyargo:$id" })
        }
}
