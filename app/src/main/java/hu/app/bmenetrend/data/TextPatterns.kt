package hu.app.bmenetrend.data

/** Compiled once: these run per vehicle, route or badge on every parse and list render. */
internal object TextPatterns {
    val HEX_COLOR = Regex("[a-fA-F0-9]{6}")
    /** G/S/Z-prefixed suburban and rail line numbers (e.g. S70, G43, Z30). */
    val RAIL_LINE_NUMBER = Regex("^[GSZgsz]\\d+.*")
    val SHORT_TRAIN_NAME = Regex("[A-Za-z0-9][A-Za-z0-9 .-]{0,14}")
    val LICENSE_PLATE = Regex("[A-Z]{3,4}\\d{3}")
    val FEED_ID_PREFIX = Regex("^(?:BKK_|volan_)", RegexOption.IGNORE_CASE)
    val PLATFORM_CODE = Regex("^[A-Z0-9]{1,4}$")
    val DESTINATION_ARROW = Regex("""\s*(?:->|→)\s*""")
    val DESTINATION_SPACED_DASH = Regex("""\s+[-–—]\s+""")
}
