package hu.app.bmenetrend.data

import org.jsoup.Jsoup
import java.time.Instant
import java.time.LocalDate

/** Reviewed official school / school-break PDF tables. The HTML trip API exposes
 * only its selected table, labelled generically Munkanapokon; it cannot establish
 * whether a trip operates during school holidays. Keep its live GPS parser separate.
 */
object ViabuszTimetableParser {
    const val SCHOOL_PATH = "documents/viabusz-menetrend-tanitas-261001.pdf"
    const val BREAK_PATH = "documents/viabusz-menetrend-szunet-260901.pdf"
    const val SCHOOL_SHA = "546466bd8d5a0afe0396015dc43252979995a7bce98346a9c9b063e63e65deb0"
    const val BREAK_SHA = "e18278f78baf63477af6737debf9352a5e3feecd869c354d5325299a0224a846"
    val routeNumbers = setOf("1", "1P", "2", "3", "4")

    fun activeCalendar(date: LocalDate): String? {
        // School table was revised on October 1; do not backdate it to September.
        if (date < LocalDate.parse("2026-10-01")) return null
        return KanyargoCalendar.activeCalendar(date)
    }

    fun validateNetwork(base: TransitNetwork, timetableHtml: String, schoolSha: String, breakSha: String, now: Instant): TransitNetwork {
        require(base.routes.all { it.providerId == "viabusz" } && base.routes.map { it.number }.toSet() == routeNumbers)
        require(base.trips.all { it.calendar in setOf("school_term", "school_break") }) { "ViaBusz requires reviewed school and holiday tables" }
        require(schoolSha == SCHOOL_SHA && breakSha == BREAK_SHA) { "ViaBusz timetable document changed" }
        val document = Jsoup.parse(timetableHtml, "https://viabusz.biatorbagy.hu/")
        val activeDocuments = document.select(".dropdown-content a[href], a.mobileMenu[href]")
            .map { it.absUrl("href").substringBefore('?').substringBefore('#') }
            .filter { it.endsWith(".pdf", ignoreCase = true) }
            .toSet()
        val knownDocuments = setOf("https://viabusz.biatorbagy.hu/$SCHOOL_PATH", "https://viabusz.biatorbagy.hu/$BREAK_PATH")
        // Check the timetable menu, not old links kept in historical news articles.
        require(activeDocuments == knownDocuments) { "ViaBusz active timetable changed" }
        val today = now.atZone(ViabuszParser.zone).toLocalDate()
        val date = today.takeIf { activeCalendar(it) != null }
        return base.copy(fetchedAt = now, serviceDate = date, providerServiceDates = mapOf("viabusz" to date), warning = null, verifiedProviderIds = setOf("viabusz"))
    }
}
