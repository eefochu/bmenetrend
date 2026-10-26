package hu.app.bmenetrend.data

/** EMMA's rail shortName can be a font glyph. Keep line names and individual train numbers separate. */
object RailSupport {
    private val line = Regex("(?:S|Z|G|EX|RE|R|TT)\\s?\\d{1,3}[A-Z]?", RegexOption.IGNORE_CASE)
    private val trainNumber = Regex("(?<![\\p{L}\\d])\\d{1,7}(?!\\d)")
    private val markup = Regex("""<[^>]+>|&(?:#\w+|\w+);""")
    fun isTrain(mode: String?) = mode == "RAIL" || mode == "TRAMTRAIN"
    fun clean(value: String?): String? = value?.trim()?.takeUnless {
        it.isBlank() || markup.containsMatchIn(it) || it.any { c -> Character.getType(c) == Character.PRIVATE_USE.toInt() }
    }
    fun number(shortName: String?, longName: String?): String {
        val short = clean(shortName)
        val long = clean(longName)
        if (long != null && line.matches(long)) return long.replace(" ", "").uppercase(java.util.Locale.ROOT)
        if (short != null && short.matches(Regex("[A-Za-z0-9][A-Za-z0-9 .-]{0,14}"))) return short
        val prefix = long?.substringBefore(' ')?.takeIf { it.length in 1..8 && it.any(Char::isLetterOrDigit) } ?: return "?"
        return if (prefix.lowercase(java.util.Locale.ROOT) in setOf("ic", "ir", "ec", "en", "rj", "rjx", "ex")) prefix.uppercase(java.util.Locale.ROOT) else prefix
    }
    fun serviceNumber(tripName: String?): String? = clean(tripName)?.let { trainNumber.find(it)?.value }
    fun label(line: String?, number: String?): String = listOfNotNull(line, number?.takeUnless { it == line }).joinToString(" ")

    fun isTrainDeparture(routeNumber: String, serviceName: String?): Boolean {
        val cleanService = serviceName?.trim()?.uppercase(java.util.Locale.ROOT).orEmpty()
        val cleanNum = routeNumber.trim().uppercase(java.util.Locale.ROOT)
        return cleanNum.matches(Regex("^[GSZgsz]\\d+.*")) ||
               cleanNum in setOf("S", "SZ", "G", "GY", "Z", "IC", "IR", "RJ", "RJX", "EC", "EN", "EX") ||
               listOf("INTERCITY", "RAILJET", "EUROCITY", "EURONIGHT", "EXPRESSZ", "GYORS", "SZEMÉLY", "ZÓNÁZÓ", "GYORSÍTOTT", "INTERRÉGIÓ", "INTERREGIO", "VONAT").any { cleanService.contains(it) }
    }

    fun canonicalBadge(number: String, serviceName: String? = null): String {
        val cleanNum = number.trim()
        val cleanService = serviceName?.trim()?.uppercase(java.util.Locale.ROOT).orEmpty()
        val numUpper = cleanNum.uppercase(java.util.Locale.ROOT)
        return when {
            cleanNum.matches(Regex("^[GSZgsz]\\d+.*")) -> cleanNum.uppercase(java.util.Locale.ROOT)
            numUpper.startsWith("IR") && cleanNum.any { it.isDigit() } -> cleanNum.uppercase(java.util.Locale.ROOT)
            cleanService.contains("INTERCITY") || numUpper == "IC" -> "IC"
            cleanService.contains("INTERRÉGIÓ") || cleanService.contains("INTERREGIO") || numUpper == "IR" -> "IR"
            cleanService.contains("RAILJET") || numUpper.startsWith("RJ") -> "rjx"
            cleanService.contains("EUROCITY") || numUpper == "EC" -> "EC"
            cleanService.contains("EURONIGHT") || numUpper == "EN" -> "EN"
            cleanService.contains("EXPRESSZ") || numUpper == "EX" -> "Ex"
            cleanService.contains("GYORSÍTOTT") && (cleanNum.isBlank() || !cleanNum.any { it.isDigit() }) -> "G"
            cleanService.contains("GYORS") || numUpper == "GY" -> "Gy"
            (cleanService.contains("ZÓNÁZÓ") || cleanService.contains("ZONAZO")) && (cleanNum.isBlank() || !cleanNum.any { it.isDigit() }) -> "Z"
            numUpper == "SZ" || (cleanService.contains("SZEMÉLY") && numUpper.startsWith("SZ")) -> "Sz"
            numUpper == "S" || cleanService.contains("SZEMÉLY") -> "S"
            cleanNum.isNotBlank() && cleanNum != "?" -> cleanNum
            else -> "S"
        }
    }
}
