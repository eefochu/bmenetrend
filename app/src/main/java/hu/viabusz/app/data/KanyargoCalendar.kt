package hu.viabusz.app.data

import java.time.LocalDate

/** School calendar: 1/2026. (VII. 31.) OGYM decree, §§ 2 and 7.
 * https://njt.jog.gov.hu/jogszabaly/2026-1-20-9M
 * Annual workday transfers are additionally required by ServiceCalendar.
 * Reviewed coverage starts with the imported September 2026 timetable.
 */
object KanyargoCalendar {
    private val firstReviewed = LocalDate.parse("2026-09-01")
    private val lastReviewed = LocalDate.parse("2026-12-31")
    private val autumn = LocalDate.parse("2026-10-23")..LocalDate.parse("2026-11-01")
    private val winter = LocalDate.parse("2026-12-19")..LocalDate.parse("2027-01-03")

    fun activeCalendar(date: LocalDate): String? {
        if (date !in firstReviewed..lastReviewed || !ServiceCalendar.isConfirmedWorkingDay(date)) return null
        return if (date in autumn || date in winter) "school_break" else "school_term"
    }
}
