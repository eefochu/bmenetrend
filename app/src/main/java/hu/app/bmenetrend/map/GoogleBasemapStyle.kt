package hu.app.bmenetrend.map

/** Local JSON styles disable Google's automatic dark palette, so supply our dark colours explicitly. */
internal object GoogleBasemapStyle {
    fun json(dark: Boolean): String {
        val palette = if (dark) """
            {"elementType":"geometry","stylers":[{"color":"#1f1f1f"}]},
            {"elementType":"labels.text.fill","stylers":[{"color":"#b9b9b9"}]},
            {"elementType":"labels.text.stroke","stylers":[{"color":"#1f1f1f"}]},
            {"featureType":"road","elementType":"geometry","stylers":[{"color":"#3c3c3c"}]},
            {"featureType":"road.highway","elementType":"geometry","stylers":[{"color":"#555555"}]},
            {"featureType":"poi.park","elementType":"geometry","stylers":[{"color":"#213529"}]},
            {"featureType":"water","elementType":"geometry","stylers":[{"color":"#142c3b"}]},
        """.trimIndent() else ""
        return """[$palette
            {"featureType":"transit.station.rail","elementType":"labels","stylers":[{"visibility":"off"}]}
        ]""".trimIndent()
    }
}
