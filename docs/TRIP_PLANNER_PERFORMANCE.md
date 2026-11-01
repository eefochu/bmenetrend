# Online trip planning — 2026-11-01

The reported failure was Herceghalom → Budapest-Nyugati, showing the generic
service-unavailable message. The live response captured for that pair contained
four itineraries that passed the actual parser at the pinned request time. The
route also succeeded on retry. This does not identify the exact failed server
response in the user's screenshot.

## Confirmed regression and fix

The earlier HTTP sharing change made the planner inherit the background client's
OkHttp dispatcher. Five same-host map/stop/departure requests could occupy every
slot and leave a routing request queued. `PlannerRequestIsolationTest` reproduced
that starvation before the fix, holding five background calls until explicitly
released. It now passes: the production planner factory uses an independent,
four-request dispatcher while retaining shared connections and TLS state.

Routing now retries one transient connection failure or HTTP 502/504, after
150 ms. It continues to honor server cooldowns and rate limits; it does not retry
timeouts, HTTP 403/429/503 or responses with a retry deadline.

## Faster completed forms and repeat submissions

After both endpoints are selected, a 250 ms debounce starts the existing national
and bus-only online searches. Foreground submission joins an already-started
request. Starting to edit or changing inputs cancels obsolete preparation.

The backend coalesces matching in-flight requests and keeps at most 16 route
entries. Completed entries expire after 10 seconds; in-flight reuse is limited
to 30 seconds. Keys include exact endpoint coordinates and bus-only mode. Reuse
permits the requested departure to advance by at most 10 seconds, validates each
itinerary against the new ready time, and refetches if none remains usable. Source
leg times are never shifted. Cancelling a prefetch does not cancel an active
foreground waiter: that waiter can start a replacement request.

These are recent **online** answers held in memory; an offline pack is not needed.
Changing network mode clears them, and the toolbar refresh explicitly bypasses
them. National results continue to appear while independent local verification
runs. Selecting a partial result remains effective when the final result contains
that itinerary.

## Verification

One limited Antigravity Gemini 3.8 Flash Medium task ran the full validation
command. It exposed an older timing-dependent departure-board test. The primary
agent fixed its transient-state assertion and a second older map-pan test's fixed
sleep, then reran the same checks in the shared checkout:

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew :app:testDevelopmentUnitTest :app:lintRelease :app:assembleRelease --console=plain
```

Final result: **534 tests, zero failures/errors, two existing skips; release lint
and assembly passed**. The new regression coverage includes dispatcher starvation,
the captured live-route parser response, cache expiry and invalidation, concurrent
request coalescing, cancellation recovery, bounded retry/cooldown handling, online
prefetch reuse and forced refresh. The prefetch test observes usable results with
local verification blocked, no additional HTTP calls on submission and no advance
of virtual time caused by submission.

The final arm64 release APK was installed on the Medium_Phone Android 16 emulator.
Herceghalom → Budapest-Nyugati returned an itinerary, its details opened, and forced
refresh returned a route. Budapest-Kelenföld → Budapest-Keleti also returned routes.
The physical Huawei was not used for this verification.

Observed final-build submission-to-first-itinerary samples were 0.875 seconds for
the first Herceghalom submission, 0.546 seconds after reselecting its endpoint and
allowing prefetch during the UI transition, and 0.579 seconds for newly selected
Kelenföld → Keleti endpoints. Each is one accessibility measurement including
driver overhead, not a render trace or statistical latency estimate. A prior
release sample was 0.997 seconds, but differing online/cache states prevent a
controlled before/after speedup claim. An earlier failed driver tap sent no input
and is excluded. These measurements cannot establish phone frame performance or
guarantee an instant uncached remote response.

Raw samples and APK hash: [planner-emulator-2026-11-01.json](../tools/perf/results/planner-emulator-2026-11-01.json).
The reusable [UI latency probe](../tools/perf/planner_latency_probe.py) requires an
owned Device session and a completed form without existing result cards; it
rejects a form whose expected endpoint labels are absent.
