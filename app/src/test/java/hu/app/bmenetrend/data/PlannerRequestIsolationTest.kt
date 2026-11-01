package hu.app.bmenetrend.data

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The planner (long-running EMMA plan calls) must not be starved by the five same-host async
 * slots of the shared dispatcher used by vehicles, stops and departure boards.
 *
 * The test keeps the production wiring: it enqueues through
 * [EmmaGraphqlClient.defaultHttpClient] and [EmmaGraphqlClient.plannerHttpClient], the latter being
 * the factory the default [hu.app.bmenetrend.PlannerViewModel] uses. Both derived clients talk to
 * the same fake HTTPS host, and every interceptor answers locally, so no real socket is opened.
 */
class PlannerRequestIsolationTest {

    @Test
    fun plannerCallReachesInterceptorWhileSharedHostSlotsAreSaturated() {
        val release = CountDownLatch(1)
        val backgroundEntered = CountDownLatch(5)
        val backgroundFinished = CountDownLatch(5)
        val plannerReached = CountDownLatch(1)
        val backgroundCalls = mutableListOf<Call>()
        var plannerCall: Call? = null

        val backgroundClient = EmmaGraphqlClient.defaultHttpClient().newBuilder()
            .addInterceptor(Interceptor { chain ->
                backgroundEntered.countDown()
                // Stay inside the interceptor: the dispatcher slot is occupied, no socket is opened.
                assertTrue("background request was not released", release.await(10, TimeUnit.SECONDS))
                syntheticResponse(chain.request(), "{\"data\":{}}")
            })
            .build()

        val plannerClient = EmmaGraphqlClient.plannerHttpClient().newBuilder()
            .addInterceptor(Interceptor { chain ->
                plannerReached.countDown()
                syntheticResponse(chain.request(), "{\"data\":{}}")
            })
            .build()

        assertNotSame(
            "planner traffic must not queue behind the default shared dispatcher",
            backgroundClient.dispatcher,
            plannerClient.dispatcher,
        )
        assertEquals("planner dispatcher is bounded independently", 4, plannerClient.dispatcher.maxRequestsPerHost)

        try {
            repeat(5) {
                val call = backgroundClient.newCall(request("background-$it"))
                backgroundCalls += call
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) = backgroundFinished.countDown()
                    override fun onResponse(call: Call, response: Response) {
                        response.close()
                        backgroundFinished.countDown()
                    }
                })
            }
            assertTrue("the five same-host background slots never filled", backgroundEntered.await(10, TimeUnit.SECONDS))

            val call = plannerClient.newCall(request("planner"))
            plannerCall = call
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = Unit
                override fun onResponse(call: Call, response: Response) = response.close()
            })

            assertTrue(
                "planner request did not reach its interceptor within 3s while the shared dispatcher's " +
                    "5 same-host slots were held by background requests: plannerHttpClient() shares " +
                    "defaultHttpClient()'s dispatcher",
                plannerReached.await(3, TimeUnit.SECONDS),
            )
        } finally {
            release.countDown()
            plannerCall?.cancel()
            backgroundCalls.forEach { it.cancel() }
            // Do not shut down the process-wide shared dispatcher: other tests keep using it.
            backgroundFinished.await(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun liveHerceghalomResponseKeepsValidItinerariesUnderPinnedReadyInstant() {
        val request = JSONObject(resourceText("/planner/herceghalom-request-live.json"))
        val variables = request.getJSONObject("variables")
        val ready = LocalDate.parse(variables.getString("date"))
            .atTime(LocalTime.parse(variables.getString("time")))
            .atZone(TripPlanner.zone)
            .toInstant()

        val origin = PlannerEndpoint("herceghalom", "Herceghalom", "", GeoPoint(47.4951, 18.7446))
        val destination = PlannerEndpoint("nyugati", "Budapest-Nyugati", "", GeoPoint(47.5106, 19.0568))
        val plan = TripPlanner.parse(
            resourceText("/planner/herceghalom-response-live.json"),
            origin,
            destination,
            requestedAt = ready,
            ready = ready,
        )

        println("live herceghalom ready=$ready itineraries=${plan.itineraries.size}")
        plan.itineraries.forEach { println("  ${it.id} start=${it.start} end=${it.end} transfers=${it.transfers}") }
        assertTrue("live EMMA response produced no feasible itineraries from $ready", plan.itineraries.isNotEmpty())
    }

    private fun request(tag: String): Request =
        Request.Builder().url("https://planner-isolation.invalid/graphql?tag=$tag").build()

    private fun syntheticResponse(request: Request, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    private fun resourceText(path: String): String =
        javaClass.getResource(path)?.readText() ?: error("missing test resource $path")
}
