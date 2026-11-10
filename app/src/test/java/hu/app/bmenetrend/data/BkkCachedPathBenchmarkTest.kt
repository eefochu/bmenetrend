package hu.app.bmenetrend.data

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

class BkkCachedPathBenchmarkTest {

    private val now = Instant.parse("2026-11-10T12:00:00Z")
    private val deakEndpoint = PlannerEndpoint("deak", "Deák Ferenc tér", "M", GeoPoint(47.497, 19.054))
    private val kelenfoldEndpoint = PlannerEndpoint("kelenfold", "Kelenföld vasútállomás", "", GeoPoint(47.464, 19.021))

    private val deakPoint = PlanPoint("Deák Ferenc tér", GeoPoint(47.497, 19.054))
    private val kelenfoldPoint = PlanPoint("Kelenföld vasútállomás", GeoPoint(47.464, 19.021))

    private fun sampleItinerary(): TripItinerary {
        val start = now.plusSeconds(120)
        val end = start.plusSeconds(600)
        val route = TransitRoute("bkk:BKK:M4", "M4", "Metro 4", emptyList(), "bkk", transportMode = "SUBWAY")
        val leg = PlanLeg(
            mode = "SUBWAY",
            start = start,
            end = end,
            distance = 3000.0,
            from = deakPoint,
            to = kelenfoldPoint,
            route = route,
            headsign = "M4",
            realtime = false,
            stayOnBoard = false,
            stops = emptyList()
        )
        return TripItinerary("itin:M4", start, end, listOf(leg))
    }

    private class EmptyPlannerBackend : PlannerBackend {
        override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> = emptyList()
        override suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant): WalkingResult = WalkingResult(emptyMap())
        override fun clearRouteCache() {}
    }

    @Test
    fun benchmarkCachedPlannerAndParsingLatency() = runBlocking {
        val bkkRow = sampleItinerary()
        val emmaBackend = EmptyPlannerBackend()
        val bkkSource = object : BkkPlannerSource {
            override suspend fun plan(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean) =
                BkkWorkerParser.PlanResult(listOf(bkkRow), 0, 1)
            override suspend fun walking(from: PlanPoint, to: PlanPoint, ready: Instant): WalkingLink? = null
            override suspend fun stops(area: MapBounds): List<TransitStop> = emptyList()
            override fun planHeadroom(): Int = 10
        }
        val backend = BkkFirstPlannerBackend(emmaBackend, bkkSource, clock = { now })

        // 1. Initial plan call populates cache
        backend.routes(deakEndpoint, kelenfoldEndpoint, now, busesOnly = false)

        // Warm up JVM
        repeat(20) {
            backend.routes(deakEndpoint, kelenfoldEndpoint, now, busesOnly = false)
        }

        // Measure cached routes calls
        val iterations = 100
        val plannerTimesNanos = LongArray(iterations)
        for (i in 0 until iterations) {
            val start = System.nanoTime()
            val result = backend.routes(deakEndpoint, kelenfoldEndpoint, now, busesOnly = false)
            val duration = System.nanoTime() - start
            plannerTimesNanos[i] = duration
            assertTrue("Cached result must not be empty", result.isNotEmpty())
        }

        plannerTimesNanos.sort()
        val p50Millis = plannerTimesNanos[iterations / 2] / 1_000_000.0
        val p95Millis = plannerTimesNanos[(iterations * 95) / 100] / 1_000_000.0
        val maxMillis = plannerTimesNanos.last() / 1_000_000.0
        val avgMillis = (plannerTimesNanos.average()) / 1_000_000.0

        // 2. Measure LiveVehicleMerge latency with 250 vehicles
        val bkkVehicles = (1..150).map { i ->
            WorkerVehicle(
                Vehicle("emma:BKK:$i", GeoPoint(47.49 + i * 0.0001, 19.05 + i * 0.0001), now, heading = 90.0, providerId = "bkk", routeNumber = "$i"),
                setOf("$i"),
                false
            )
        }
        val emmaVehicles = (100..200).map { i ->
            Vehicle("emma:1:$i", GeoPoint(47.48 + i * 0.0001, 19.04 + i * 0.0001), now, heading = 45.0, providerId = "mav", trainNumber = "$i")
        }
        val bounds = MapBounds(19.00, 47.40, 19.10, 47.60)

        // Warm up merge
        repeat(20) {
            LiveVehicleMerge.merge(bkkVehicles, bounds, emmaVehicles)
        }

        val mergeTimesNanos = LongArray(iterations)
        for (i in 0 until iterations) {
            val start = System.nanoTime()
            val merged = LiveVehicleMerge.merge(bkkVehicles, bounds, emmaVehicles)
            val duration = System.nanoTime() - start
            mergeTimesNanos[i] = duration
            assertTrue(merged.isNotEmpty())
        }
        mergeTimesNanos.sort()
        val mergeP50Millis = mergeTimesNanos[iterations / 2] / 1_000_000.0
        val mergeP95Millis = mergeTimesNanos[(iterations * 95) / 100] / 1_000_000.0

        // 3. Measure BkkWorkerClient cached get latency
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"status":"OK","data":{"list":[]}}""".toResponseBody()).build()
        }.build()
        val workerClient = BkkWorkerClient("https://worker.test".toHttpUrl(), client, { now }, BkkRequestBudget())
        workerClient.get("vehicles-for-location", mapOf("lat" to "47.49", "lon" to "19.05"))

        repeat(20) { workerClient.get("vehicles-for-location", mapOf("lat" to "47.49", "lon" to "19.05")) }
        val clientTimesNanos = LongArray(iterations)
        for (i in 0 until iterations) {
            val start = System.nanoTime()
            workerClient.get("vehicles-for-location", mapOf("lat" to "47.49", "lon" to "19.05"))
            clientTimesNanos[i] = System.nanoTime() - start
        }
        clientTimesNanos.sort()
        val clientP50Millis = clientTimesNanos[iterations / 2] / 1_000_000.0
        val clientP95Millis = clientTimesNanos[(iterations * 95) / 100] / 1_000_000.0

        // Save evidence to /private/tmp/bkk-primary-android-validation/ and legacy validation dir
        val primaryOutDir = File("/private/tmp/bkk-primary-android-validation")
        primaryOutDir.mkdirs()
        val outDir = File("/private/tmp/bkk-android-gemini-final")
        outDir.mkdirs()
        val legacyOutDir = File("/private/tmp/bkk-android-gemini-validation")
        legacyOutDir.mkdirs()

        val jsonReport = JSONObject()
            .put("cachedPlannerRoutes", JSONObject()
                .put("iterations", iterations)
                .put("avgMillis", avgMillis)
                .put("p50Millis", p50Millis)
                .put("p95Millis", p95Millis)
                .put("maxMillis", maxMillis)
                .put("targetThresholdMillis", 100.0)
                .put("passed", p95Millis <= 100.0))
            .put("liveVehicleMerge", JSONObject()
                .put("vehicleCount", bkkVehicles.size + emmaVehicles.size)
                .put("p50Millis", mergeP50Millis)
                .put("p95Millis", mergeP95Millis))
            .put("workerClientCachedGet", JSONObject()
                .put("p50Millis", clientP50Millis)
                .put("p95Millis", clientP95Millis))
            .put("environment", JSONObject()
                .put("jvmHost", System.getProperty("os.name") + " " + System.getProperty("os.arch"))
                .put("javaVersion", System.getProperty("java.version") ?: "unknown")
                .put("caveat", "Measurements taken in JVM unit test harness. Local JVM timings reflect algorithmic efficiency and zero-network caching, not device UI rendering."))

        val jsonText = jsonReport.toString(2)
        val summaryText = """
            ===================================================================
            BKK-First Android Validation Latency Report
            ===================================================================
            Cached Planner Routes (target <= 100ms):
              avg: ${"%.3f".format(avgMillis)} ms
              p50: ${"%.3f".format(p50Millis)} ms
              p95: ${"%.3f".format(p95Millis)} ms
              max: ${"%.3f".format(maxMillis)} ms
              PASS: ${p95Millis <= 100.0}

            Live Vehicle Merge (250 vehicles):
              p50: ${"%.3f".format(mergeP50Millis)} ms
              p95: ${"%.3f".format(mergeP95Millis)} ms

            Worker Client In-Memory Cached GET:
              p50: ${"%.3f".format(clientP50Millis)} ms
              p95: ${"%.3f".format(clientP95Millis)} ms

            Notice:
            Measured in local JVM unit test environment. Production UI frame rendering
            should be evaluated on a physical device with release/R8 build.
            ===================================================================
        """.trimIndent()

        File(primaryOutDir, "latency_report.json").writeText(jsonText)
        File(primaryOutDir, "summary.txt").writeText(summaryText)
        File(outDir, "latency_report.json").writeText(jsonText)
        File(outDir, "summary.txt").writeText(summaryText)
        File(legacyOutDir, "latency_report.json").writeText(jsonText)
        File(legacyOutDir, "summary.txt").writeText(summaryText)

        // Strict assertion: cached repeat call must be well under 100ms (typically < 1ms on JVM)
        assertTrue("Cached planner p95 latency must be <= 100ms, was $p95Millis ms", p95Millis <= 100.0)
    }
}
