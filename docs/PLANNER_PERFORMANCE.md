# Trip-planning latency — 2026-10-26

The planner combines EMMA national routes (including an independent BUS/COACH search)
with the reviewed ViaBusz, KanyarGO and HOMM timetables. This change reduces waiting
in that pipeline; it does not change the three-alternative selection policy.

## Reproducible measurement

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew :app:testDebugUnitTest \
  --tests '*PlannerLatencyTest' --tests '*HybridTripPlannerTest'
```

`PlannerLatencyTest.bundledNetworkSearchLatency` uses the real bundled, merged local
stop graph for a Budaörs → Biatorbágyi Innovatív Technikum area search on 26 October
2026 at 07:00 Budapest time. Provider verification is supplied by the fixture.
It executes the actual hybrid orchestrator and the same request budget as the HTTP
backend. Only the backend responses and waits are simulated: one second for loading
the network, five seconds per national query and one second per eight walking links.
National interchange references are fixture stops colocated with the bundled stops.
The national fixture supplies a feasible 20-minute Volán journey; connecting queries
return no additional national journeys. All CPU work uses the test scheduler too.

| Measurement | Before (`7a25fdb`) | After |
| --- | ---: | ---: |
| Walking starts | 5,000 ms | 1,000 ms |
| Complete search | 40,000 ms | 32,000 ms |
| National route requests | 17 | 17 |
| Walking links considered | 231 | 231 |
| Leading alternative | Direct Volán fixture | Same fixture |

This is a **20% reduction in simulated orchestration latency**, not a phone speed or
frame-rate claim. The original pipeline was replayed with the same scheduler and
failed the new early-start/latency assertions. In a separate slow-national-server
regression, the first usable local route moved from 31,000 ms to 2,000 ms. Final
results still wait for national coverage. Live initial EMMA queries for the same
area took 8.156 s (all transit) and 7.138 s (BUS/COACH) in one observational check;
those variable server timings are not used as a before/after benchmark.

## Changes and limits

- Local access, walking and timetable routing run alongside national searches.
  Both national searches are still joined before using their interchange references.
  Failures, cancellation and partial-coverage reporting remain intact.
- HTTP work is capped at four concurrent requests, up from three. Walking remains
  capped at three batches of eight aliases, so the previous maximum of 24 active
  walking calculations is retained. The trade-off is one more overlapping HTTP
  request; existing HTTP timeouts and rate-limit backoff remain in force.
- Identical national queries share a deferred result within one search, keyed by
  exact coordinates, departure instant and transport-mode restriction. Endpoint
  names/source IDs do not create duplicate queries. A new search gets fresh results;
  no realtime response cache or departure-time rounding is introduced.
- A local prefix arriving after the best known complete journey plus the selector's
  maximum 20-minute allowance is excluded from gateway requests. Such a journey
  cannot be selected even with a zero-duration onward connection. There is no pruning
  by operator, distance or arbitrary route count. The shared delay constant also
  drives the existing selector, and no bound is applied without a complete journey.

Regression coverage includes all four local/national operators in one journey,
weekend Volán fixtures, infeasible transfers, unavailable services, pre-index planning,
independent bus-only requests, exact-query reuse, late-prefix pruning and cancellation.
These are network/orchestration changes; no Compose drawing or marker layout changes
are involved.

## Validation

All 308 unit/Robolectric/Compose UI tests pass; Android lint reports zero errors and
90 warnings. Both optimized debug and release APKs build with R8/resource shrinking.
The release APK completed a live Vasútállomás (Biatorbágy) → Budaörs, Vasút utca
search on the API 36 arm64 emulator, rendered its S10 result, opened the journey
details and scrolled to the destination without an app crash. This smoke check is
not a before/after device timing benchmark.
