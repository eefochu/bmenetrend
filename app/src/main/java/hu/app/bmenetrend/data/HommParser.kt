package hu.app.bmenetrend.data

import org.jsoup.Jsoup
import java.net.URLDecoder
import java.time.Instant

/** Reviewed official PDFs, no live feed. Source changes invalidate the imported
 * timetable instead of silently continuing to present stale countdowns.
 */
object HommParser {
    const val BASE_URL = "https://homm.hu/"
    const val TIMETABLE_URL = "https://homm.hu/helyi-kozlekedes/"
    const val SCHOOL_PATH = "www/menetrend-budaors/Közlekedési rend tanítási napokon.pdf"
    const val BREAK_PATH = "www/menetrend-budaors/Közlekedési rend tanítási szünetben.pdf"
    const val SCHOOL_URL = BASE_URL + SCHOOL_PATH
    const val BREAK_URL = BASE_URL + BREAK_PATH
    const val SCHOOL_SHA = "c7fb61b7abbc6fbf1fc65b828870c8fe2d805dc3e6b2f03f4bdb05a505b0ff82"
    const val BREAK_SHA = "792cc9db653af9c453ade424075568f55098ca5a2a6fac8028374e66d902d593"
    val routeNumbers = setOf("288", "289")
    private val knownDocuments = setOf(SCHOOL_URL, BREAK_URL) + listOf(
        "Átszállási időpontok.pdf",
        "288 Budaörs ltp - Kamaraerdői Iglói köz.pdf",
        "288 Kamaraerdő Iglói köz - Budaörs ltp.pdf",
        "289 Budaörsi ltp - Ötvös utca.pdf",
        "289 Ötvös utca - Budaörsi ltp.pdf",
    ).map { "${BASE_URL}www/menetrend-budaors/$it" }

    fun validateNetwork(base: TransitNetwork, timetableHtml: String, schoolSha: String, breakSha: String, now: Instant): TransitNetwork {
        require(base.routes.all { it.providerId == "homm" } && base.routes.map { it.number }.toSet() == routeNumbers)
        require(schoolSha == SCHOOL_SHA && breakSha == BREAK_SHA) { "HOMM timetable document changed" }
        val document = Jsoup.parse(timetableHtml, TIMETABLE_URL)
        val links = document.select("a[href]").map {
            URLDecoder.decode(it.absUrl("href").substringBefore('?').substringBefore('#').replace("+", "%2B"), "UTF-8")
        }.filter { it.startsWith("${BASE_URL}www/menetrend-budaors/") && it.endsWith(".pdf", ignoreCase = true) }.toSet()
        require(links.containsAll(setOf(SCHOOL_URL, BREAK_URL)) && links.all { it in knownDocuments }) { "HOMM active timetable changed" }
        val today = now.atZone(ViabuszParser.zone).toLocalDate()
        val serviceDate = today.takeIf { HommCalendar.activeCalendar(it) != null }
        return base.copy(fetchedAt = now, serviceDate = serviceDate, providerServiceDates = mapOf("homm" to serviceDate), warning = null)
    }
}
