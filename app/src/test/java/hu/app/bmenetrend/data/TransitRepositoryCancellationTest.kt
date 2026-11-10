package hu.app.bmenetrend.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import hu.app.bmenetrend.offline.NetworkAccessPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.file.Files
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class TransitRepositoryCancellationTest {

    private lateinit var tempDir: File
    private lateinit var testContext: Context
    private lateinit var serverSocket: ServerSocket
    private var serverPort: Int = 0

    private val acceptedSockets = Collections.synchronizedList(mutableListOf<Socket>())
    private val acceptedLatch = CountDownLatch(1)
    private val serverRunning = AtomicBoolean(true)
    private lateinit var serverThread: Thread

    private val recordedCalls = Collections.synchronizedList(mutableListOf<Call>())
    private val canceledCalls = Collections.synchronizedList(mutableListOf<Call>())

    private val eventListener = object : EventListener() {
        override fun callStart(call: Call) {
            recordedCalls.add(call)
        }
        override fun canceled(call: Call) {
            canceledCalls.add(call)
        }
    }

    private lateinit var hangingHttpClient: OkHttpClient

    @Before
    fun setUp() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        NetworkAccessPolicy.setOffline(appContext, false)
        NetworkAccessPolicy.onConnectivityChanged(true)

        // Isolated temporary directory to guarantee cold cache (no cached networks)
        tempDir = Files.createTempDirectory("transit-cancellation-test").toFile()
        testContext = object : ContextWrapper(appContext) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = tempDir
        }

        // ServerSocket that accepts HTTP connections and holds response to simulate hanging upstream
        serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverPort = serverSocket.localPort

        serverThread = Thread({
            try {
                while (serverRunning.get()) {
                    val socket = serverSocket.accept()
                    acceptedSockets.add(socket)
                    acceptedLatch.countDown()
                }
            } catch (_: SocketException) {
            } catch (_: IOException) {
            }
        }, "HangingHttpServer").apply {
            isDaemon = true
            start()
        }

        // OkHttpClient that redirects requests to local serverSocket held response
        hangingHttpClient = OkHttpClient.Builder()
            .eventListener(eventListener)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val rewrittenUrl = originalRequest.url.newBuilder()
                    .scheme("http")
                    .host("127.0.0.1")
                    .port(serverPort)
                    .build()
                chain.proceed(originalRequest.newBuilder().url(rewrittenUrl).build())
            }
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        serverRunning.set(false)
        runCatching { serverSocket.close() }
        synchronized(acceptedSockets) {
            acceptedSockets.forEach { runCatching { it.close() } }
            acceptedSockets.clear()
        }
        serverThread.interrupt()
        runCatching { serverThread.join(1000) }
        hangingHttpClient.dispatcher.cancelAll()
        hangingHttpClient.connectionPool.evictAll()
        hangingHttpClient.dispatcher.executorService.shutdownNow()
        tempDir.deleteRecursively()

        val appContext = ApplicationProvider.getApplicationContext<Context>()
        NetworkAccessPolicy.setOffline(appContext, false)
        NetworkAccessPolicy.onConnectivityChanged(true)
    }

    @Test
    fun cancellingPlanningNetworkPromptlyCancelsUnderlyingHttpCalls() = runBlocking {
        val repository = TransitRepository(testContext, hangingHttpClient)

        val job = launch(Dispatchers.IO) {
            repository.planningNetwork()
        }

        // Wait until at least one HTTP connection is accepted and actively held
        assertTrue("HTTP request did not reach held socket in time", acceptedLatch.await(5, TimeUnit.SECONDS))
        assertTrue("Expected at least one Call started", recordedCalls.isNotEmpty())
        assertFalse("Call should not be canceled while in-flight", recordedCalls.first().isCanceled())

        // Measure wall-clock cancellation time
        val startWallClock = System.currentTimeMillis()
        job.cancelAndJoin()
        val cancelDurationMs = System.currentTimeMillis() - startWallClock
        println("LOCAL_HTTP_CANCEL_MS=$cancelDurationMs")

        // Wall-clock cancellation must be prompt (not waiting for 15s/20s HTTP timeout)
        assertTrue(
            "Cancelling planningNetwork took $cancelDurationMs ms, expected < 1500 ms",
            cancelDurationMs < 1500L
        )

        // All initiated calls must be cancelled
        assertTrue("Expected all started calls to be canceled", recordedCalls.all { it.isCanceled() })
        assertTrue("Expected canceledCalls to be recorded by EventListener", canceledCalls.isNotEmpty())

        // Confirm server-side socket observed EOF or reset from client cancellation
        val firstSocket = acceptedSockets.firstOrNull()
        if (firstSocket != null && !firstSocket.isClosed) {
            firstSocket.soTimeout = 1000
            val reachedEof = try {
                // The request headers may already be buffered; drain them before checking EOF.
                val input = firstSocket.getInputStream()
                val buffer = ByteArray(4096)
                while (input.read(buffer) != -1) { }
                true
            } catch (_: SocketException) {
                true // Peer reset is also cancellation; a read timeout must fail the test.
            }
            assertTrue("Expected server socket to see EOF or reset upon client cancellation", reachedEof)
        }
    }

    @Test
    fun hybridTripPlannerHealthyDirectResultReturnsPromptlyWhilePlanningNetworkHangs() = runBlocking {
        val repository = TransitRepository(testContext, hangingHttpClient)

        val ready = Instant.parse("2026-10-22T05:00:00Z")
        val origin = PlannerEndpoint("stop:a", "Origin Stop", "", GeoPoint(47.40, 18.8))
        val destination = PlannerEndpoint("stop:b", "Destination Stop", "", GeoPoint(47.42, 18.8))

        val directLeg = PlanLeg(
            mode = "BUS",
            start = ready.plusSeconds(300),
            end = ready.plusSeconds(900),
            distance = 3000.0,
            from = PlanPoint(origin.name, origin.position),
            to = PlanPoint(destination.name, destination.position),
            route = TransitRoute("volan:1", "1", "Volán", emptyList(), "volan", transportMode = "BUS"),
            headsign = "Direct Bus",
            realtime = false,
            stayOnBoard = false,
            stops = emptyList(),
            source = DataSource.EMMA
        )
        val directItinerary = TripItinerary("volan", directLeg.start, directLeg.end, listOf(directLeg))

        val backend = object : PlannerBackend {
            override suspend fun routes(
                from: PlannerEndpoint,
                to: PlannerEndpoint,
                ready: Instant,
                busesOnly: Boolean
            ): List<TripItinerary> {
                if (busesOnly) return emptyList()
                // Wait until local verification HTTP request has actually been issued to the held socket
                withContext(Dispatchers.IO) {
                    assertTrue("Local verification did not reach the held socket", acceptedLatch.await(5, TimeUnit.SECONDS))
                }
                return listOf(directItinerary)
            }

            override suspend fun walking(
                pairs: List<Pair<PlanPoint, PlanPoint>>,
                ready: Instant
            ): WalkingResult {
                return WalkingResult(emptyMap())
            }
        }

        val planner = HybridTripPlanner(backend)

        val startWallClock = System.currentTimeMillis()
        val plan = planner.plan(
            origin = origin,
            destination = destination,
            requested = ready,
            ready = ready,
            nationalStops = emptyList(),
            loadNetwork = { repository.planningNetwork() }
        )
        val durationMs = System.currentTimeMillis() - startWallClock
        println("PLAN_WITH_HANGING_LOCAL_HTTP_MS=$durationMs")

        // Wall-clock plan execution must complete promptly despite hanging local HTTP requests
        assertTrue(
            "HybridTripPlanner.plan took $durationMs ms, expected < 2000 ms",
            durationMs < 2000L
        )

        // Result should contain the healthy direct itinerary
        assertTrue("Plan should contain itineraries", plan.itineraries.isNotEmpty())
        assertTrue("Itinerary should match direct route", plan.itineraries.any { it.legs.any { leg -> leg.headsign == "Direct Bus" } })

        // Underlying in-flight OkHttp calls must be cancelled
        assertTrue("Expected started calls to be recorded", recordedCalls.isNotEmpty())
        assertTrue("Expected all started calls to be canceled after searchJob cancellation", recordedCalls.all { it.isCanceled() })
    }
}
