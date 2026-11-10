# BKK Worker integration

The Android app calls the keyless Cloudflare Worker configured in `app/build.gradle.kts` as `BuildConfig.BKK_WORKER_URL`. The BKK key is stored only in the Worker's `BKK_API_KEY` secret. Deployment and endpoint details are in [the Worker README](../tools/bkk-worker/README.md).

## Source selection

- The live map polls a bounded, grid-aligned viewport through BKK anywhere in Hungary alongside the existing nationwide EMMA feed. BKK wins shared vehicle identities and supplies the position/time for matched trains. EMMA supplies missing railway vehicles and preserves coverage outside the queried BKK area and for other existing providers.
- Shared underlying vehicle numbers, including coupled sets, prevent cross-feed duplicates. Railway stops use shared source identities so saved stops and offline data remain usable. A nearby bus or metro stop is a different entity from a railway station.
- Nearby, favourite and selected departure boards use the Worker's six-stop batches wherever a shared BKK identity is known, including railway station codes. Only failed/unavailable stops go to direct EMMA. A valid empty BKK board remains a successful answer. BKK-identified line variants and trip details try the Worker first and use EMMA when BKK cannot supply them.
- Map search and planner station suggestions put BKK results first; the local/national catalogue and EMMA supplement missing identities without overwriting BKK records. Vehicle search can query BKK lines and exact IDs outside the current viewport, including full/prefixed train IDs. Existing stop identities remain stable for favourites and offline data.
- Planning tries BKK for accepted Hungarian endpoints, including bus/coach-only requests. A usable complete BKK answer returns without calling the national EMMA planner. Empty, failed, timed-out, over-budget or partly unrepresentable responses trigger EMMA fallback; usable BKK rows remain first. An intact BKK-router walk/boarding or transfer connection uses its calculated timing, with predecessor, spatial-continuity and non-overlap checks. Local and mixed-journey joins retain the app's extra boarding/transfer buffers. No leg times are shifted. Valid Volán/COACH legs are supported as BKK data.
- App planning publishes one stable journey after selecting a usable answer. Direct results do not wait for unrelated local/gateway enrichment; remaining work is cancelled. A local/hybrid answer has one 500 ms settling window, with a ten-second overall online deadline. Suggestions with gaps exceeding sixty minutes are excluded without changing source timestamps. An explicit replan or input change is required to replace the displayed result.
- FUTÁR's `volan_…` stops, routes, trips and live vehicles are parsed throughout search, map, boards and detail views. Numeric Volán platform codes and vehicle plates use the existing `hkir:hkir_…` identities, so the national catalogue, favourites and markers do not gain duplicate copies. Known `V-R-200-<three digits>` agglomeration line IDs can request the corresponding FUTÁR line; unrelated national route namespaces are not guessed from their display number. Raw FUTÁR route/trip IDs remain explicit when no shared identity is established. Mixed BKK/Volán bus platforms retain both operators' routes and departures. For a national Volán vehicle with no known FUTÁR trip, detail loading first looks up the exact vehicle plate at BKK and uses the returned trip; EMMA serves unavailable records. This does not imply nationwide Volán real-time coverage from BKK.
- Bounded station discovery supplements BKK with missing EMMA stop identities even when BKK returned some stops. A non-empty BKK result alone is not treated as proof of complete nationwide coverage. Offline and cached catalogues stay available.

**Hosted MÁV limitation:** MÁV's server currently refuses Cloudflare-originated requests with HTTP 403. The Worker therefore reports `fallback.status: unavailable` when appropriate and keeps BKK responses working. Android uses its existing direct EMMA client for railway supplementation; the hosted fallback is not required for that native path. The static web test page does not have this native fallback and cannot claim complete hosted MÁV coverage.

The Worker accepts a maximum 0.09° half-span per map query. Larger or out-of-country Android views use the existing EMMA map path. This avoids unbounded tile fan-out and excessive BKK requests. No BKK credential is bundled into the APK.

## Verifying the source

Source labels name the feed that supplied a displayed result, independently of its operator. Vehicles, station/vehicle search rows, planner suggestions/results, board footers, nearby/favourite stations and line/trip details show `BKK API`, `EMMA`, or a mixed label when applicable. A merged train distinguishes **BKK position / EMMA details**. Unknown/offline source fields remain unset rather than being labelled BKK. Reused itinerary receipt times are preserved, and older reused results show their age.

The two HTTP hosts alone do not establish the source of a particular displayed item. Cloudflare request logs establish requests reached the Worker; the app's source metadata and regression tests establish which returned data was selected.

## Response time and freshness

- Worker responses are cached briefly at Cloudflare. Cached data preserves its original timestamps; it is not relabelled as a fresh live observation.
- The Android HTTP client coalesces identical requests, uses a bounded in-memory response cache, cancels requests on cancellation/offline transitions, and respects its own request budget and server `Retry-After` limits.
- JSON decoding and transformations run off the main thread. A feasible completed planner result, including national-only alternatives, is reused briefly without waiting for another network request.
- Android plans request `compact=true`. This retains inline legs, times, routes, endpoint/platform details and intermediate stops while removing ignored elevation, step geometry and pattern data. Default web planner responses retain route geometry.
- Recent independent desktop measurements reduced the tested planner response from 958,027 to 15,883 UTF-8 bytes. Five cached compact responses measured 55 ms p95 including download and JSON parsing. These are small desktop network samples, not device UI guarantees. Fresh planning still depends on upstream calculation, and occasional network spikes remain possible.

The app budgets 60 Worker calls and 8 plans per minute, beneath the Worker's per-IP 120/12 limits. A hybrid planning search can ask for a normal and a bus-only plan; optional walking links use a bounded allowance while reserving capacity for main journeys. Mobile carrier users may share an IP. Bounded batches, viewports and cache reuse limit pressure; limits do not guarantee dedicated capacity per device.

## Validation

The integration has dedicated parser, identity merge, client/cache/budget, repository, planner and ViewModel tests. Use the `development` variant for unit tests; this project's `debug` APK is optimized with R8 and is not debuggable.

```sh
./gradlew :app:testDevelopmentUnitTest
./gradlew :app:assembleDebug
```

From `tools/bkk-worker`:

```sh
npm ci
npm test
node scripts/benchmark.js
node scripts/compact-benchmark.js
```

The public benchmark scripts use no API key and make bounded requests. They measure headers, body download and JSON parsing separately, report actual cache states, and stop on HTTP 429. Desktop/JVM cache measurements do not establish Android frame or on-device response timings.

Local direct-test keys (`tools/bkk-map-test/key.js`), environment secrets, dependency directories, Worker state, logs and APK outputs are ignored by Git. Source, lockfiles and safe secret examples stay versioned.
