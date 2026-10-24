package hu.app.bmenetrend.data

object NetworkMerger {
    /** Preserve provider IDs and dates independently: one outage cannot invalidate another operator. */
    fun merge(networks: List<TransitNetwork>): TransitNetwork {
        require(networks.isNotEmpty())
        val dates = networks.flatMap { network ->
            network.routes.map { it.providerId }.distinct().map { provider ->
                provider to if (provider in network.providerServiceDates) network.providerServiceDates[provider] else network.serviceDate
            }
        }.toMap()
        val stops = networks.flatMap { it.stops }
        val routes = networks.flatMap { it.routes }
        require(stops.map { it.id }.distinct().size == stops.size && routes.map { it.id }.distinct().size == routes.size)
        return SharedStops.merge(TransitNetwork(stops, routes, networks.flatMap { it.trips }, networks.maxOf { it.fetchedAt },
            networks.map { it.source }.distinct().joinToString("; "), dates.values.filterNotNull().distinct().singleOrNull(),
            "provider_calendars", networks.mapNotNull { it.warning }.distinct().takeIf { it.isNotEmpty() }?.joinToString("\n"), dates,
            networks.flatMap { network -> network.routes.map { it.providerId }.distinct().mapNotNull { id ->
                (network.providerWarnings[id] ?: network.warning)?.let { id to it }
            } }.toMap(), networks.flatMap { it.verifiedProviderIds }.toSet()))
    }
}
