# Trip planner

The Map, Plan / Tervezés and Favorites destinations retain their screen state. The planner owns a separate ViewModel and StateFlow. Search combines indexed stops with cached Photon places; planning starts explicitly. Map polling pauses while the planner is visible. Changing endpoints, departure time or canceling a search cancels the old request, and old generations cannot overwrite new results.

## Operators and alternatives

The planner combines two sources:

- EMMA/MÁVPlusz routes include Volán (`BUS` / `COACH`), BKK, rail, metro, HÉV, tram and trolleybus. The general `TRANSIT` request has no operator blacklist. A separate `WALK + BUS + COACH` request finds useful bus alternatives even when EMMA ranks several train journeys first.
- `LocalTimetableRouter` searches the reviewed ViaBusz, KanyarGO and HOMM scheduled trips already shipped with the app. Local buses may form an entire journey or connect to EMMA routes at either end, including local → Volán → local trips. Local GPS positions without trip identities are never used to invent a route or a realtime timetable.

EMMA previously supplied five alternatives and the app retained only three. On the reported Budaörs–Biatorbágy Saturday journey, the Volán option was fourth and was lost. Each national search now requests up to twelve alternatives. Final results preserve the earliest option, the best bus-only and Volán options, and available local-operator alternatives, then fill up to eight distinct boarding/alighting route patterns. They remain ordered by arrival, transfers and walking distance. Later departures on an identical pattern do not fill the list.

National results appear while local timetables and connections are being searched. A failed secondary request does not remove successful routes; a partial-coverage notice explains when more routes may exist. A completely unavailable planner is distinguished from a successful search with no feasible journeys.

## Local timetable validity

`TransitNetwork.verifiedProviderIds` records successful HTML/PDF fingerprint validation independently of today's service date. It is cached in the optional JSON field of the existing schema and unioned when networks merge. Older caches without proof are revalidated before planning. A changed source clears verification; an ordinary non-operating day does not. This matters when planning on Saturday for a later working day.

The local router selects each trip's school-term/break calendar for the requested service date, including public holidays and working-day transfers. It also examines previous-day overnight trips and the next service day within a 24-hour search horizon. Times above 24:00 retain their original service date. Wall-clock times are converted in Europe/Budapest; nonexistent or repeated DST times are skipped rather than guessed.

The imported calendars are reviewed through December 31, 2026. Beyond that range local rides are unavailable and coverage is marked partial. The national planner still uses EMMA's published timetable horizon. On Saturday, October 24, 2026, ViaBusz and KanyarGO have no regular service in these imports; HOMM uses its weekend/school-break timetable. Restricted school services retain their registration-required notice.

## Connections and walking

All access, egress and transfer walking durations come from EMMA's street router. Geographic distance only chooses candidate links and gateways; a missing street route never falls back to a straight-line walking estimate. Walks are directed, routed in aliased batches of at most eight, and cached by both endpoint coordinates. At most three EMMA HTTP requests run concurrently across walking and national searches.

Local access and egress candidates are within 1,200 metres of the original endpoint. Local platform-transfer candidates are within 200 metres. Exact reviewed shared platforms use the existing canonical IDs; proximity never merges unrelated stops, platforms or departure boards. A local search maintains separate earliest-arrival labels by stop and boarding count and scans every applicable local trip. Actual boarding/alighting stops, intermediate stops, source times and calendar restrictions are preserved.

For combined journeys, the planner evaluates up to three distinct gateways at each end, near national stops or boarding/alighting points returned by the source. Candidate selection considers both rail and road interchanges. It searches national continuations between the original endpoints and these local gateways, including combinations with a local leg at both ends. If the map stop index is still loading, a bounded EMMA area query supplies interchange candidates independently. This bounded mobile search does not claim to enumerate every possible national/local interchange or guarantee the globally optimal journey.

## Timing and presentation

Leave now adds two minutes of preparation. Choosing a date and time uses that exact start time. Walking speed is 1.15 m/s. Initial boarding requires 60 seconds and changing vehicles requires 180 seconds in addition to transfer walking. The national request also applies 30 seconds of alighting slack and omits canceled services. Local/national boundaries receive the same transfer allowance; final assembled journeys are checked again. Staying aboard a through service does not create a transfer. At most five boardings / four transfers are allowed overall.

Source times are preserved; overlapping legs, missed departures and impossible transfers are rejected. Only source-marked realtime legs receive a live label. Local timetables are scheduled. Cards and detail timelines show walking, waiting, line badges, source times and intermediate stops without inventing intermediate arrival times. Past journeys prompt replanning.

Origin/destination coordinates and departure time are sent to EMMA. Local walking links and gateway coordinates are also sent to route those connections. Using current location includes those GPS coordinates. Photon receives search text, not GPS location. No additional account, private API key or backend is needed.

## Validation

Deterministic tests cover national train/metro/bus responses, the formerly hidden Volán alternative, all three local operators, local → Volán → local combinations, missed connections, source failures, partial walking aliases, directed walking caches, source verification and cache round trips, Saturday calendars, future-date planning, overnight trips, DST conversion, restricted school services and cancellation.

A live end-to-end check on October 24, 2026 verified the reported Kamaraerdő–Biatorbágyi Innovatív Technikum journey with Volán 760 / 762 and HOMM 288 connections. All three local source document fingerprints passed validation. Only a trimmed public-stop-to-school Volán response is committed in `app/src/test/resources/planner/volan-weekend.json`; the user's private access address and live harness are excluded. The live check is separate from the deterministic test suite.

EMMA uses the [OpenTripPlanner plan protocol](https://docs.opentripplanner.org/api/dev-2.x/graphql-gtfs/queries/plan). Its deployed query fields and bus/coach and walking mode combinations were checked against the live endpoint.
