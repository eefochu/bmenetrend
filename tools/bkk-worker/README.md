# BKK proxy for Cloudflare Workers

The app sends `GET /api/<endpoint>.json?...` to this Worker. The Worker validates parameters, adds its BKK secret, calls the fixed BKK host, removes echoed keys from the response, and returns BKK's JSON structure. No database is needed.

## Railway fallback

**Hosted verification:** BKK requests work on the deployed Cloudflare Worker. The MÁV endpoint currently returns HTTP 403 to Worker-originated requests, even though the same queries succeed from the local machine. Hosted responses therefore report `fallback.status: "unavailable"` and retain BKK data until MÁV access from the Worker is available. Local merge/detail tests and live local-source checks pass; this does not establish working hosted MÁV coverage. Native app integration can keep direct EMMA access as the railway supplement.

BKK is the primary source. The Worker supplements successful BKK map/search responses with missing **RAIL and TRAMTRAIN** vehicles and railway stops from MÁVPlusz/EMMA. It does not import EMMA's buses, trams, metro or HÉV. Trip planning remains on BKK; selecting a fallback station passes its coordinates to the existing planner.

- Vehicles match by their underlying vehicle number, including components of coupled BKK sets. Shared identities retain BKK data. Fallback vehicles use the same `BKK_<vehicle-number>` identity so a source change does not create a second marker. `source: "emma"` identifies a fallback. Positions older than five minutes or without valid coordinates are excluded; fallback fills a BKK item without coordinates.
- Railway stops match by shared stop/parent-station codes. Different IDs can also match when the normalized railway station name agrees and their coordinates are within 150 m. The BKK record wins. EMMA platforms sharing one parent become one fallback railway station. Nearby bus/metro stops and distant stations with the same name remain distinct.
- An EMMA ID is namespaced as `emma:1:...` for fallback stops, routes and trips. A fallback stop's departure board and a fallback train's trip geometry/stops are fetched directly from EMMA and converted to the existing JSON shape. These are live source identities, not guessed trip matches.
- Search also matches train numbers, names, destination and route labels. Matching vehicle IDs are checked against BKK before using the EMMA version. At most 20 matching trains are returned per search.
- `fallback.status` reports `ok` or `unavailable`. If EMMA times out/fails, the primary BKK data still returns and is cached for at most five seconds, so an outage does not make every map refresh a slow upstream request. MÁV HTTP 403 gets a one-minute retry cooldown; other failures get ten seconds. A BKK authentication error is still reported as an error rather than being hidden by EMMA.
- EMMA's nationwide rail snapshot is cached for 10 seconds at each Cloudflare location and filtered to the requested viewport; stop queries cache for five minutes. Simultaneous identical EMMA fetches in one Worker instance share a request, with a short retry cooldown on failures. The fallback is supplemental live coverage, not a promise that every train has a fresh position.

The test page displays source information and the count of fallback trains. This feature requires **Backend URL…** mode; direct BKK mode cannot merge feeds.

## Deploy

Requires Node.js and a Cloudflare account. From this directory:

```sh
npm ci
npx wrangler login
npm run deploy
npx wrangler secret put BKK_API_KEY
```

Paste your BKK key at the **secret prompt**, not into source code or `wrangler.jsonc`. The first deploy returns configuration errors on API calls until the secret is set. Wrangler prints your `https://bmenetrend-bkk-proxy.<your-subdomain>.workers.dev` address.

Open the map test page, click **Backend URL…**, and paste that address. It reloads in proxy mode: it does not load `key.js`, send a BKK key, or require a key in the browser. Existing local key files/browser storage from direct mode are not deleted. Do not publish `key.js` with a hosted page.

An Android client uses the same URL, without a key:

```text
https://YOUR-WORKER.workers.dev/api/search.json?query=Keleti&lat=47.4979&lon=19.0402&includeReferences=stops
```

`GET /health` checks that the Worker runs; it does not check BKK credentials.

## Supported requests

All use API version 4. Available BKK endpoints are `search`, `vehicles`, `vehicles-for-location`, `vehicles-for-route`, `stops-for-location`, `arrivals-and-departures-for-stop`, `trip-details`, `route-details`, `route-details-for-stop`, `references`, `schedule-for-stop`, and `plan-trip`. They use BKK's existing parameter names. The Worker also provides the bounded batch endpoint described below.

- Location queries: `lat`, `lon`, and either both `latSpan`/`lonSpan` (maximum **0.09 each**, centre to edge) or `radius` (maximum **5000 m**). Zoom in if the test page reports a query-size error.
- Planner: `fromPlace` and `toPlace` as `Name::47.4979,19.0402`; `mode=TRANSIT,WALK`, `BUS,WALK`, `BUS,COACH,WALK`, or `WALK`; optional `date=YYYY-MM-DD`, `time=HH:mm`, `arriveBy=true`. Times are Budapest local time. Up to 3 requested alternatives.
- Android planner clients can add `compact=true`: inline itinerary, route, time and intermediate-stop fields remain, while unused step geometry, elevation arrays, repeated metro trip alternatives and reference dictionaries are removed. The proxy consumes this option; it is not sent to BKK. Default responses retain geometry for the web test page. Compact and full results have separate cache entries.
- Only Hungary-area coordinates and supported parameters are accepted. Client-supplied `key`, arbitrary URLs, and unsupported endpoints are rejected.
- Line details: `route-details?routeId=...` with optional `date=YYYYMMDD` and `related=true`; `route-details-for-stop?stopId=...`; `schedule-for-stop?stopId=...&date=YYYYMMDD`.
- Bounded reference lookup: `references` requires at least one of `stopId`, `routeId`, `tripId`, `agencyId`, `alertId`. ID lists are limited to ten per field; this is not an unrestricted catalogue dump.

### Multi-platform departure boards

`GET /api/arrivals-and-departures-for-stops.json?stopId=BKK_F01037,BKK_F01038&includeReferences=routes,trips,stops`

At most six stop IDs are accepted. The Worker fetches their BKK boards with at most three upstream calls in flight, merges reference dictionaries, and returns the existing `data.entry.stopTimes` shape. Each row retains its stop ID. `data.successfulStopIds` identifies successful boards (including genuinely empty ones); `data.failedStopIds` tells the native client exactly which stops need EMMA fallback. A failed platform does not discard the others. Namespaced EMMA-only stop IDs are reported as failed rather than sent to BKK.

Fully successful batches cache for ten seconds; partial failures are not cached. A batch costs one incoming proxy request but up to six BKK upstream calls. It is bounded batching, not an upstream quota exemption.

## Cache and limits

- Vehicles: 5 seconds; arrivals: 10 seconds; trip details: 30 seconds; combined searches: 10 seconds; stops: 5 minutes. Raw EMMA station searches cache for 5 minutes. Namespaced EMMA details use their own short upstream cache.
- Exact repeated trip plans cache for five seconds; changed places/options have separate entries. Only successful responses are cached. Cache keys contain no BKK secret, and API/planner error responses are not cached. This small freshness window applies even to “leave now” requests.
- Cache entries are shared for identical queries at a Cloudflare location. Different bounding boxes do not automatically share an upstream request. This is not a centralized live-data polling service.
- Request limits: **120 per minute per IP**, with a separate **12 plans per minute per IP**. These are our proxy limits, not BKK's official quotas. All API calls, including cache hits, count. Cloudflare's limiter is approximate and local to each location; it is not a strict worldwide cap. Users behind one IP share a limit.
- HTTP 429 includes `Retry-After: 60`. The app should back off. Missing rate-limit bindings cause HTTP 503 rather than leaving the proxy unprotected.
- `X-Proxy-Cache: HIT`, `MISS`, or `BYPASS` helps interpret timings. The test page's benchmark measures **the proxy** when proxy mode is enabled; repeated calls can hit the cache.

This is a public proxy with basic abuse controls, not authenticated app access. The BKK key stays private, but anyone who knows the proxy URL can call its allowed operations within those limits. Do not embed a shared proxy password in an APK to try to solve this. Add verified user sessions or app attestation if stronger abuse controls become necessary.

`ALLOWED_ORIGINS` defaults to `*` for testing, including pages opened from disk. To limit browser access, set a comma-separated list of exact origins in `wrangler.jsonc`, then redeploy. CORS does not authenticate Android apps or command-line clients. Do not put secrets in `vars`.

## Local development and checks

Copy `.dev.vars.example` to `.dev.vars`, put a key there, and run `npm run dev`. `.dev.vars` is ignored by Git. Use `http://localhost:8787` in the test page's Backend URL setting.

```sh
npm test
npx wrangler deploy --dry-run
```

References: [BKK API schema](https://opendata.bkk.hu/docs/futar-openapi.yaml), [Worker secrets](https://developers.cloudflare.com/workers/configuration/secrets/), [rate limits and their locality](https://developers.cloudflare.com/workers/runtime-apis/bindings/rate-limit/), [Cache API](https://developers.cloudflare.com/workers/runtime-apis/cache/).
