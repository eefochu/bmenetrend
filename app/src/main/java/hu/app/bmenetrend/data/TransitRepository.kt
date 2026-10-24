package hu.app.bmenetrend.data

import android.content.Context
import hu.app.bmenetrend.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Public website adapters; no authentication, private endpoints or secret keys. */
class TransitRepository(context: Context) {
    private val appContext = context.applicationContext
    private data class Provider(val id: String, val name: String, val url: String, val caller: String?, val asset: String)
    private val providers = listOf(
        Provider("viabusz", "ViaBusz", "https://viabusz.biatorbagy.hu/", "Viabusz1012", "viabusz-calendar-network.json"),
        Provider("kanyargo", "KanyarGO", "https://kanyargo.paty.hu/", "KanyarGo1012", "kanyargo-network.json"),
        Provider("homm", "HOMM", "https://homm.hu/", null, "homm-network.json"),
    )
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).build()
    private fun message(id: Int, vararg args: Any) = appContext.getString(id, *args)
    private fun bundled(provider: Provider) = NetworkJson.decode(appContext.assets.open(provider.asset).bufferedReader().use { it.readText() })
    private fun cache(provider: Provider) = File(appContext.filesDir, "${provider.id}-network-v3.json")

    fun bundledNetwork(): TransitNetwork = NetworkMerger.merge(providers.map { checkedDate(bundled(it), it, Instant.now()) })

    suspend fun loadNetwork(force: Boolean = false): TransitNetwork = withContext(Dispatchers.IO) {
        coroutineScope { NetworkMerger.merge(providers.map { provider -> async { loadProvider(provider, force) } }.awaitAll()) }
    }

    suspend fun planningNetwork(): TransitNetwork = withContext(Dispatchers.IO) {
        coroutineScope { NetworkMerger.merge(providers.map { provider -> async { loadProvider(provider, false, requireVerification = true) } }.awaitAll()) }
    }

    private suspend fun loadProvider(provider: Provider, force: Boolean, requireVerification: Boolean = false): TransitNetwork {
        val now = Instant.now()
        val today = now.atZone(ViabuszParser.zone).toLocalDate()
        val file = cache(provider)
        val saved = runCatching { if (file.isFile) NetworkJson.decode(file.readText()) else null }.getOrNull()
        if (!force && saved != null && (!requireVerification || provider.id in saved.verifiedProviderIds) && saved.fetchedAt.atZone(ViabuszParser.zone).toLocalDate() == today &&
            Duration.between(saved.fetchedAt, now).seconds in 0..86_400) return checkedDate(saved, provider, now)
        return try {
            val network = coroutineScope {
                val sourcePaths = when (provider.id) {
                    "viabusz" -> Triple("", ViabuszTimetableParser.SCHOOL_PATH, ViabuszTimetableParser.BREAK_PATH)
                    "homm" -> Triple("helyi-kozlekedes/", HommParser.SCHOOL_PATH, HommParser.BREAK_PATH)
                    else -> Triple("Menetrend", KanyargoParser.SCHOOL_PATH, KanyargoParser.BREAK_PATH)
                }
                val page = async { get(provider, sourcePaths.first).toString(Charsets.UTF_8) }
                val school = async { KanyargoParser.sha256(get(provider, sourcePaths.second)) }
                val holiday = async { KanyargoParser.sha256(get(provider, sourcePaths.third)) }
                val base = bundled(provider)
                when (provider.id) {
                    "viabusz" -> ViabuszTimetableParser.validateNetwork(base, page.await(), school.await(), holiday.await(), now)
                    "homm" -> HommParser.validateNetwork(base, page.await(), school.await(), holiday.await(), now)
                    else -> KanyargoParser.validateNetwork(base, page.await(), school.await(), holiday.await(), now)
                }
            }
            // Disk write failure must not throw away a valid provider response.
            runCatching {
                val temporary = File(file.parentFile, "${file.name}.tmp")
                temporary.writeText(NetworkJson.encode(network))
                if (!temporary.renameTo(file)) temporary.delete()
            }
            checkedDate(network, provider, now)
        } catch (failure: CancellationException) { throw failure
        } catch (failure: Exception) {
            val fallback = checkedDate(saved ?: bundled(provider), provider, now)
            val error = if (failure is IOException) R.string.data_network_unavailable else R.string.data_source_changed
            // A source format/version change invalidates even a same-day cached countdown.
            if (failure !is IOException) {
                val invalidated = fallback.copy(fetchedAt = now, serviceDate = null, providerServiceDates = mapOf(provider.id to null), verifiedProviderIds = emptySet(),
                    warning = message(error, provider.name))
                runCatching { file.writeText(NetworkJson.encode(invalidated)) }
                invalidated
            } else fallback.copy(warning = message(error, provider.name))
        }
    }

    suspend fun refresh(): LiveSnapshot = withContext(Dispatchers.IO) {
        coroutineScope {
            val results = providers.filter { it.caller != null }.map { provider -> async { refreshProvider(provider) } }.awaitAll()
            // An upstream outage belongs to these providers, not the whole app or EMMA.
            LiveSnapshot(results.flatMap { it.vehicles }, results.flatMap { it.arrivals }, Instant.now(),
                results.mapNotNull { it.warning }.distinct().joinToString("\n"),
                vehicleDataAvailable = results.any { it.vehicleDataAvailable },
                providerWarnings = results.flatMap { it.providerWarnings.entries }.associate { it.toPair() })
        }
    }

    private suspend fun refreshProvider(provider: Provider): LiveSnapshot = coroutineScope {
        val vehicleCall = async { fetchResult(provider, "getWehicleDatas") }
        val arrivalCall = async { fetchResult(provider, if (provider.id == "viabusz") "getStartTimesMP" else "getStartTimes",
            if (provider.id == "viabusz") mapOf("selectedRoute" to "10") else emptyMap()) }
        val vehicleBody = vehicleCall.await(); val arrivalBody = arrivalCall.await()
        val now = Instant.now()
        val vehicles = vehicleBody.getOrNull()?.let {
            if (provider.id == "viabusz") ViabuszParser.parseVehicles(it, now) else KanyargoParser.parseVehicles(it, now)
        }.orEmpty()
        val verified = vehicleBody.getOrNull()?.let {
            if (provider.id == "viabusz") ViabuszParser.parseVehicleArrivals(it, vehicles) else KanyargoParser.parseVehicleArrivals(it, vehicles)
        }.orEmpty()
        val aggregate = arrivalBody.getOrNull()?.let {
            if (provider.id == "viabusz") ViabuszParser.parseAggregateArrivals(it) else KanyargoParser.parseAggregateArrivals(it)
        }.orEmpty()
        val resource = when {
            vehicleBody.isFailure -> R.string.data_live_unavailable
            vehicles.isEmpty() -> R.string.data_no_fresh_vehicles
            else -> R.string.data_live_positions_only
        }
        LiveSnapshot(vehicles, verified + aggregate, now, message(resource, provider.name), vehicleDataAvailable = vehicleBody.isSuccess,
            providerWarnings = mapOf(provider.id to message(resource, provider.name)))
    }

    fun departures(network: TransitNetwork, live: LiveSnapshot?, stopId: String,
                   now: Instant = Instant.now(), windowMinutes: Int = 90): List<Departure> =
        DepartureCalculator.departures(network, live, stopId, now, windowMinutes)

    private fun checkedDate(network: TransitNetwork, provider: Provider, now: Instant): TransitNetwork {
        val today = now.atZone(ViabuszParser.zone).toLocalDate()
        val savedDate = if (provider.id in network.providerServiceDates) network.providerServiceDates[provider.id] else network.serviceDate
        val active = when (provider.id) {
            "homm" -> HommCalendar.activeCalendar(today) != null
            "viabusz" -> ViabuszTimetableParser.activeCalendar(today) != null
            else -> KanyargoCalendar.activeCalendar(today) != null
        }
        val valid = savedDate == today && active
        val warning = when {
            valid -> null
            !ServiceCalendar.isYearReviewed(today.year) -> message(R.string.data_calendar_unreviewed, provider.name, today.year)
            today.isBefore(java.time.LocalDate.parse("2026-09-01")) -> message(R.string.data_no_verified_timetable, provider.name)
            !active -> message(R.string.data_no_service_today, provider.name)
            else -> message(R.string.data_no_verified_timetable, provider.name)
        }
        return network.copy(serviceDate = today.takeIf { valid }, providerServiceDates = mapOf(provider.id to today.takeIf { valid }), warning = warning)
    }

    private fun fetchResult(provider: Provider, service: String, extra: Map<String, String> = emptyMap()): Result<String> = try {
        Result.success(post(provider, "WebServiceConfigurator.php", mapOf("caller" to requireNotNull(provider.caller), "serviceName" to service, "ct" to "data") + extra))
    } catch (failure: IOException) { Result.failure(failure) }

    private fun post(provider: Provider, path: String, values: Map<String, String>): String {
        val body = FormBody.Builder().apply { values.forEach { (key,value) -> add(key, value) } }.build()
        val request = Request.Builder().url(provider.url + path).header("User-Agent", "BMenetrendAndroid/0.3 (native transit viewer)").post(body).build()
        val text = execute(request).toString(Charsets.UTF_8)
        if (text.isBlank() || text.startsWith("Error;", true)) throw IOException("Provider returned no usable data")
        return text
    }
    private fun get(provider: Provider, path: String): ByteArray = execute(Request.Builder().url(provider.url + path)
        .header("User-Agent", "BMenetrendAndroid/0.3 (native transit viewer)").get().build())
    private fun execute(request: Request): ByteArray = client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        response.body?.bytes() ?: throw IOException("Empty response")
    }
}
