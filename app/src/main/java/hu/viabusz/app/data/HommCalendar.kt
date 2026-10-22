package hu.viabusz.app.data

import java.time.DayOfWeek
import java.time.LocalDate

/** HOMM's second table explicitly covers Saturdays, Sundays, public holidays and
 * school breaks, unlike KanyarGO's working-day-only service. Reviewed school term:
 * 1/2026. (VII. 31.) OGYM decree, §§ 2 and 7; annual transfers: ServiceCalendar.
 */
object HommCalendar {
    private val reviewed = LocalDate.parse("2026-09-01")..LocalDate.parse("2026-12-31")
    private val autumn = LocalDate.parse("2026-10-23")..LocalDate.parse("2026-11-01")
    private val winter = LocalDate.parse("2026-12-19")..LocalDate.parse("2027-01-03")

    fun activeCalendar(date: LocalDate): String? {
        if (date !in reviewed || !ServiceCalendar.isYearReviewed(date.year)) return null
        val weekend = date.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        return if (weekend || !ServiceCalendar.isConfirmedWorkingDay(date) || date in autumn || date in winter) {
            "school_break"
        } else "school_term"
    }
}
