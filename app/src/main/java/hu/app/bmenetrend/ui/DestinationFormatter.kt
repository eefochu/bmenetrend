package hu.app.bmenetrend.ui

/** Formats departure destination strings, stripping origin/via legs and styling clean terminal directions. */
object DestinationFormatter {
    private val RAIL_TERMINALS = setOf(
        "budapest-déli",
        "budapest-keleti",
        "budapest-nyugati",
        "budapest-kelenföld",
        "budapest-józsefváros",
    )

    private val COMMON_TOWNS = setOf(
        "etyek", "sóskút", "biatorbágy", "budaörs", "budakeszi", "páty", "zsámbék",
        "telki", "herceghalom", "bicske", "győr", "érd", "százhalombatta", "törökbálint",
        "pusztazámor", "tárnok", "diósd", "perbál", "tök", "budajenő", "nagykovácsi",
        "remeteszőlős", "solymár", "pilisvörösvár", "gödöllő", "vác", "szentendre",
        "dunakeszi", "fót", "monor", "cegléd", "vecsés", "gyál", "szigetszentmiklós",
        "budapest"
    )

    fun resolveBudapestTerminal(routeNumber: String?): String {
        val num = routeNumber?.filter { it.isDigit() }?.toIntOrNull()
        return when {
            num != null && (num in 781..795 || num in listOf(22, 222)) -> "Budapest, Széll Kálmán tér"
            num != null && (num in 300..321) -> "Budapest, Újpest-Városkapu"
            num != null && (num in 800..840) -> "Budapest, Árpád híd"
            num != null && (num in 400..499) -> "Budapest, Stadion autóbusz-pályaudvar"
            num != null && (num in 600..661) -> "Budapest, Népliget"
            // Agglomeration buses heading to Budapest (lines 760-779, 710-759, 40, 88, 141, etc., or general western corridor)
            else -> "Budapest, Kelenföld vasútállomás"
        }
    }

    fun clean(raw: String, routeNumber: String? = null): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.lowercase() in RAIL_TERMINALS) return trimmed

        // Handle arrows (e.g. A -> B or A → B)
        val arrowParts = trimmed.split(Regex("""\s*(?:->|→)\s*""")).filter { it.isNotBlank() }
        val candidate = if (arrowParts.size > 1) {
            arrowParts.last().trim()
        } else {
            // Handle spaced dashes: ' - ' or ' – ' or ' — '
            val spacedDashParts = trimmed.split(Regex("""\s+[-–—]\s+""")).filter { it.isNotBlank() }
            if (spacedDashParts.size > 1) {
                spacedDashParts.last().trim()
            } else {
                // Handle unspaced or hyphenated sequences like Budapest-Biatorbágy-Etyek Botpuszta
                val parts = trimmed.split("-").map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.size >= 2) {
                    val lastTwo = "${parts[parts.size - 2]}-${parts.last()}".lowercase()
                    if (lastTwo in RAIL_TERMINALS) {
                        "${parts[parts.size - 2]}-${parts.last()}"
                    } else {
                        parts.last()
                    }
                } else {
                    trimmed
                }
            }
        }

        val formatted = formatTownAndStop(candidate)
        if (formatted.equals("budapest", ignoreCase = true)) {
            return resolveBudapestTerminal(routeNumber)
        }
        return formatted
    }

    private fun formatTownAndStop(text: String): String {
        if (text.contains(",")) return text
        val spaceIdx = text.indexOf(' ')
        if (spaceIdx > 0) {
            val town = text.substring(0, spaceIdx).trim()
            var stop = text.substring(spaceIdx + 1).trim()
            if (stop.startsWith("(") && stop.endsWith(")")) {
                stop = stop.substring(1, stop.length - 1).trim()
            }
            if (town.lowercase() in COMMON_TOWNS && stop.isNotEmpty()) {
                return "$town, $stop"
            }
        }
        return text
    }
}
