package hu.app.bmenetrend.data

/** Operators supplied by reviewed local timetables alongside national EMMA journeys. */
enum class LocalPlanningService(val providerId: String, val displayName: String) {
    VIABUSZ("viabusz", "ViaBusz"), HOMM("homm", "HOMM"), KANYARGO("kanyargo", "KanyarGO");

    companion object {
        val providerIds: Set<String> = entries.map { it.providerId }.toSet()
    }
}
