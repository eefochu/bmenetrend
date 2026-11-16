package hu.app.bmenetrend.map

/** Local JSON styles disable Google's automatic dark palette, so supply our dark colours explicitly. */
internal object GoogleBasemapStyle {
    fun json(dark: Boolean): String {
        val palette = if (dark) """
            {"elementType":"geometry","stylers":[{"color":"#1f1f1f"}]},
            {"elementType":"labels.text.fill","stylers":[{"color":"#ffffff"}]},
            {"elementType":"labels.text.stroke","stylers":[{"color":"#1f1f1f"}]},
            {"featureType":"road","elementType":"geometry","stylers":[{"color":"#585a5e"}]},
            {"featureType":"road","elementType":"geometry.stroke","stylers":[{"color":"#444548"}]},
            {"featureType":"road.local","elementType":"geometry.fill","stylers":[{"color":"#585a5e"},{"weight":1}]},
            {"featureType":"road.local","elementType":"geometry.stroke","stylers":[{"color":"#444548"},{"weight":2}]},
            {"featureType":"road.arterial","elementType":"geometry","stylers":[{"color":"#79644f"}]},
            {"featureType":"road.arterial","elementType":"geometry.stroke","stylers":[{"color":"#54473a"}]},
            {"featureType":"road.highway","elementType":"geometry","stylers":[{"color":"#d6a370"}]},
            {"featureType":"road.highway","elementType":"geometry.stroke","stylers":[{"color":"#775a41"}]},
            {"featureType":"road.highway","elementType":"labels.text.fill","stylers":[{"color":"#ffffff"}]},
            {"featureType":"road.highway","elementType":"labels.text.stroke","stylers":[{"color":"#29231d"}]},
            {"featureType":"transit.line","elementType":"geometry","stylers":[{"color":"#8d99a6"}]},
            {"featureType":"landscape","elementType":"geometry","stylers":[{"color":"#1f1f1f"}]},
            {"featureType":"landscape.man_made","elementType":"geometry","stylers":[{"color":"#1f1f1f"}]},
            {"featureType":"landscape.man_made","elementType":"geometry.stroke","stylers":[{"color":"#383a3d"}]},
            {"featureType":"landscape.natural","elementType":"geometry","stylers":[{"color":"#1f1f1f"}]},
            {"featureType":"landscape.natural.landcover","elementType":"geometry","stylers":[{"color":"#1f1f1f"}]},
            {"featureType":"landscape.natural.terrain","elementType":"geometry","stylers":[{"color":"#1f1f1f"}]},
            {"featureType":"poi","elementType":"geometry","stylers":[{"color":"#2d2e31"}]},
            {"featureType":"poi.school","elementType":"geometry","stylers":[{"color":"#2e2b23"}]},
            {"featureType":"poi.medical","elementType":"geometry","stylers":[{"color":"#34272a"}]},
            {"featureType":"poi.park","elementType":"geometry","stylers":[{"color":"#284d36"}]},
            {"featureType":"water","elementType":"geometry","stylers":[{"color":"#163f56"}]},
        """.trimIndent() else ""
        return """[$palette
            {"featureType":"road","elementType":"geometry","stylers":[{"visibility":"on"}]},
            {"featureType":"road.local","elementType":"geometry","stylers":[{"visibility":"on"}]},
            {"featureType":"transit.line","elementType":"geometry","stylers":[{"visibility":"on"}]},
            {"featureType":"transit.station.rail","elementType":"labels","stylers":[{"visibility":"off"}]}
        ]""".trimIndent()
    }
}
