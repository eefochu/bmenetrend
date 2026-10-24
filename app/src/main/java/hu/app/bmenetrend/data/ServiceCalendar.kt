package hu.app.bmenetrend.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay

/** Calendar for the public timetable's “Munkanapokon” label.
 * Annual transfers must be checked against the promulgated decree before adding a year.
 * 2026: https://njt.jog.gov.hu/jogszabaly/2025-10-20-2X
 * Statutory holidays: 2012. évi I. törvény, 102. § (1).
 */
object ServiceCalendar {
    private data class AnnualTransfers(val restDays: Set<MonthDay>, val workingDays: Set<MonthDay>)
    private fun days(vararg values: String) = values.map(MonthDay::parse).toSet()
    private val reviewedYears = mapOf(
        2026 to AnnualTransfers(
            restDays = days("--01-02", "--08-21", "--12-24"),
            workingDays = days("--01-10", "--08-08", "--12-12"),
        ),
        // Add 2027 only after checking its official annual decree; not by copying 2026.
    )
    private val fixedHolidays = days("--01-01", "--03-15", "--05-01", "--08-20", "--10-23", "--11-01", "--12-25", "--12-26")

    fun publicHolidays(year: Int): Set<LocalDate> {
        val easter = easterSunday(year)
        return fixedHolidays.map { it.atYear(year) }.toSet() + setOf(
            easter.minusDays(2), easter.plusDays(1), easter.plusDays(50),
        )
    }

    fun isConfirmedWorkingDay(date: LocalDate): Boolean {
        val transfers = reviewedYears[date.year] ?: return false
        val day = MonthDay.from(date)
        return date !in publicHolidays(date.year) && day !in transfers.restDays &&
            (day in transfers.workingDays || date.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
    }

    fun serviceDate(date: LocalDate, label: String): LocalDate? =
        date.takeIf { label.equals("Munkanapokon", true) && isConfirmedWorkingDay(it) }

    fun isYearReviewed(year: Int): Boolean = year in reviewedYears

    /** Gregorian Meeus/Jones/Butcher computus; arithmetic only, independent of device zone. */
    private fun easterSunday(year: Int): LocalDate {
        val a = year % 19; val b = year / 100; val c = year % 100
        val d = b / 4; val e = b % 4; val f = (b + 8) / 25; val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4; val k = c % 4; val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val combined = h + l - 7 * m + 114
        return LocalDate.of(year, combined / 31, combined % 31 + 1)
    }
}
