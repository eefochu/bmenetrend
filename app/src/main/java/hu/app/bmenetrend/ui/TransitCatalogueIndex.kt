package hu.app.bmenetrend.ui

import hu.app.bmenetrend.data.TransitRoute
import hu.app.bmenetrend.data.TransitStop

/** Share immutable catalogue derivatives across countdown, camera and loading-state copies. */
internal class TransitCatalogueIndex<T>(val local: List<T>, val national: List<T>, private val id: (T) -> String) {
    val values: List<T> by lazy {
        when {
            local.isEmpty() -> national
            national.isEmpty() -> local
            else -> local + national
        }
    }
    val byId: Map<String, T> by lazy {
        HashMap<String, T>(local.size + national.size).apply {
            for (item in local) put(id(item), item)
            for (item in national) put(id(item), item)
        }
    }
}

/** One retained generation per catalogue; old UiStates retain their own immutable index. */
internal object TransitCatalogueIndexes {
    private var stops: TransitCatalogueIndex<TransitStop>? = null
    private var routes: TransitCatalogueIndex<TransitRoute>? = null

    @Synchronized
    fun stops(local: List<TransitStop>, national: List<TransitStop>): TransitCatalogueIndex<TransitStop> {
        return stops?.takeIf { it.local === local && it.national === national }
            ?: TransitCatalogueIndex(local, national, TransitStop::id).also { stops = it }
    }

    @Synchronized
    fun routes(local: List<TransitRoute>, national: List<TransitRoute>): TransitCatalogueIndex<TransitRoute> {
        return routes?.takeIf { it.local === local && it.national === national }
            ?: TransitCatalogueIndex(local, national, TransitRoute::id).also { routes = it }
    }
}
