package hu.app.bmenetrend.data

/** MÁV encodes HÉV short names as font markup; the public line name is in longName. */
object HevSupport {
    private val lineName = Regex("H[5-9](?:A|E)?", RegexOption.IGNORE_CASE)
    fun number(shortName: String?, longName: String?): String? =
        listOfNotNull(longName, shortName).firstOrNull { lineName.matches(it.trim()) }?.trim()?.uppercase(java.util.Locale.ROOT)
    fun accepts(mode: String?, shortName: String?, longName: String?) = mode == "SUBURBAN_RAILWAY" && number(shortName, longName) != null
    fun color(number: String): String = when (number.removePrefix("H").firstOrNull()) {
        '5' -> "7B116B"
        '6' -> "824B00"
        '7' -> "EF7800"
        else -> "EB6482"
    }
}
