# Trip planner

The Map and Plan / Tervezés destinations use Material 3 bottom navigation and retain their screen state. The planner has its own ViewModel and StateFlow. Map vehicle polling and departure-board work pause while the planner is visible; the static stop catalogue remains available. Search is debounced, cancellable, and combines the existing indexed stop search with cached Photon place search. Planning is explicit, not triggered on each keystroke.

## Routing and timing

Planning uses the existing public EMMA/MÁVPlusz GraphQL endpoint and its verified `plan` query. No direct BKK API, private key or additional server is used.

- Leave now requests departure after two minutes of preparation. Choose a date and time requests that exact start time in Europe/Budapest, including days ahead. Walking, boarding and transfer allowances still apply. Past departures and ambiguous/nonexistent clock-change times are rejected; the wall-time-only API cannot distinguish repeated hours. Material calendar dates are converted from UTC date identifiers before combining them with Budapest time.
- The walking speed is 1.15 m/s (about 4.1 km/h). EMMA supplies actual street-network walking legs, including crossings, elevation and station access. Walking time is not calculated from straight-line distance.
- Boarding slack is 60 seconds, alighting slack 30 seconds, and the minimum transfer allowance is 180 seconds in addition to walking between vehicles.
- Canceled services are explicitly omitted by the routing request.
- Source times are preserved. The parser rejects overlapping legs, already-missed starts and transfers without the required time. Staying aboard a through vehicle does not require a new transfer.
- Up to four transfers are allowed, so journeys with several practical changes are not excluded by the source default.
- Up to five source alternatives are requested; the earliest arriving feasible three are shown. Ranking is by arrival time, then transfers and walking distance, rather than onboard duration alone.
- Only source-marked realtime legs receive a live label. Results are not silently moved to another departure. A reminder appears when a displayed journey has already started, and the user can plan again.
- Planning is available in Hungary using services and future timetables covered by EMMA. It does not invent missing local operators, fares or ticket availability. The source may return no journeys beyond its published timetable horizon.

Times use Europe/Budapest, including date rollover and daylight saving time. Network work is cancellable, parsing/indexing run off the UI thread, and stale query generations cannot overwrite newer input.

## Validation

Public source responses captured on 2026-10-23 in `app/src/test/resources/planner/` cover direct M2 journeys with access/egress walks and a real M2–M3 transfer. Unit tests cover arrival ranking, walking/boarding feasibility, transfer walking plus slack, through vehicles, stale departures, malformed source data and Budapest date rollover. The request and response fields were checked against the live endpoint; its schema introspection is disabled.

The timing options are documented by [OpenTripPlanner](https://docs.opentripplanner.org/api/dev-2.x/graphql-gtfs/queries/plan). Source walking times can be faster than the configured flat-ground speed on individual sloped or mechanically assisted segments; validation preserves those times rather than rejecting legitimate journeys.

## Journey presentation

Result cards show route icons/badges, walking minutes, transfers, total duration and a proportional colored bar. Each leg uses the existing line palette; walking uses the theme tertiary color and waits use a neutral color. The bars include all waiting gaps, rather than allocating the whole trip to vehicles alone. Crossing midnight shows the arrival date too.

Tapping a card opens a dedicated, virtualized detail timeline. Solid route-colored segments, dotted walking segments and neutral boarding/transfer waits connect the timed boarding and alighting points. Platform codes and source realtime flags are preserved. Intermediate stop names expand on demand; no intermediate arrival times are invented. Both the toolbar and Android back return to the previous result-list position.

Tomorrow and seven-day-ahead requests were verified against EMMA on 2026-10-23, including the winter-time date offset. The seven-day response is retained as `future.json`. Tests cover explicit future readiness separately from fetch time, summer/winter timezone conversion, past and DST-ambiguous dates, complete segment accounting including waits, and midnight crossings.
