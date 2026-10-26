package hu.app.bmenetrend.data

/** Describe the source's mode/operator scope, without inventing fares or boarding rules. */
enum class TransportServiceType {
    INTERURBAN_BUS, LOCAL_BUS, CITY_BUS, BUS, TRAM, TROLLEYBUS, METRO, SUBURBAN_RAIL, RAIL, FERRY;
    companion object {
        fun of(provider: String,mode: String?): TransportServiceType = when(mode) {
            "TRAM" -> TRAM
            "TROLLEYBUS" -> TROLLEYBUS
            "SUBWAY" -> METRO
            "SUBURBAN_RAILWAY" -> SUBURBAN_RAIL
            "RAIL","TRAIN","TRAMTRAIN" -> RAIL
            "FERRY" -> FERRY
            else -> when(provider) {
                "volan" -> INTERURBAN_BUS
                "viabusz","kanyargo","homm" -> LOCAL_BUS
                "bkk" -> CITY_BUS
                "mav" -> RAIL
                "hev" -> SUBURBAN_RAIL
                else -> BUS
            }
        }
    }
}
