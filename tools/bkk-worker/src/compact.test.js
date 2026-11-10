import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import worker, { compactPlan } from './index.js';

const secret = 'super-private-bkk-key-98765';
const makeEnv = () => ({
  BKK_API_KEY: secret,
  ALLOWED_ORIGINS: '*',
  REQUEST_LIMITER: { limit: async () => ({ success: true }) },
  PLAN_LIMITER: { limit: async () => ({ success: true }) }
});

const basePlanQuery = '/api/plan-trip.json?fromPlace=Deak::47.4976,19.0540&toPlace=Keleti::47.5002,19.0838&mode=TRANSIT,WALK';

function createFullPlanFixture() {
  const startMs = 1794313600000;
  const midMs = 1794313700000;
  const endMs = 1794314000000;
  return {
    status: 'OK',
    code: 200,
    version: 4,
    currentTime: startMs,
    data: {
      limitExceeded: false,
      references: {
        routes: {
          'BKK_5200': { id: 'BKK_5200', shortName: 'M2', longName: 'Metro 2', type: 'SUBWAY', color: 'E41F18' }
        },
        trips: {
          'BKK_TRIP_M2_1': { tripHeadsign: 'Örs vezér tere', directionId: '0' }
        },
        stops: {
          'BKK_F00961': { id: 'BKK_F00961', name: 'Deák Ferenc tér', lat: 47.497593, lon: 19.053857, platformCode: '2' },
          'BKK_F01336': { id: 'BKK_F01336', name: 'Keleti pályaudvar', lat: 47.499683, lon: 19.082881, platformCode: '1' }
        }
      },
      entry: {
        requestParameters: {
          fromPlace: 'Deak::47.4976,19.0540',
          toPlace: 'Keleti::47.5002,19.0838',
          mode: 'TRANSIT,WALK',
          key: secret
        },
        plan: {
          date: 1794313600000,
          from: { name: 'Deák Ferenc tér', lat: 47.4976, lon: 19.0540 },
          to: { name: 'Keleti pályaudvar', lat: 47.5002, lon: 19.0838 },
          itineraries: [
            {
              duration: 400,
              startTime: startMs,
              endTime: endMs,
              walkTime: 60,
              transitTime: 300,
              waitingTime: 40,
              walkDistance: 80.5,
              transfers: 0,
              generalizedCost: 520,
              // Unused itinerary-level properties
              bikeTime: 0,
              bikeDistance: 0,
              elevationLost: 0,
              elevationGained: 0,
              patternItineraries: [{ id: 'pattern_1' }],
              patternDuration: 400,
              patternFrequency: 180,
              legs: [
                {
                  mode: 'WALK',
                  startTime: startMs,
                  endTime: midMs,
                  departureDelay: 0,
                  arrivalDelay: 0,
                  distance: 80.5,
                  realTime: false,
                  interlineWithPreviousLeg: false,
                  transitLeg: false,
                  from: {
                    name: 'Deák Ferenc tér',
                    lat: 47.4976,
                    lon: 19.0540,
                    stopId: 'BKK_F00961',
                    platformCode: '2',
                    departure: startMs
                  },
                  to: {
                    name: 'Deák Ferenc tér M2',
                    lat: 47.497593,
                    lon: 19.053857,
                    stopId: 'BKK_F00961',
                    platformCode: '2',
                    arrival: midMs
                  },
                  // Unused leg-level properties
                  legGeometry: { points: 'k~j`H{q`bB_c@_c@' },
                  elevation: [[0, 100], [80.5, 101]],
                  steps: [{ distance: 80.5, relativeDirection: 'DEPART', streetName: 'Aluljáró' }]
                },
                {
                  mode: 'SUBWAY',
                  startTime: midMs,
                  endTime: endMs,
                  departureDelay: 10,
                  arrivalDelay: 5,
                  distance: 2800.0,
                  agencyId: 'BKK',
                  agencyName: 'BKV Zrt.',
                  routeId: 'BKK_5200',
                  routeShortName: 'M2',
                  routeLongName: 'Metro 2',
                  routeColor: 'E41F18',
                  routeTextColor: 'FFFFFF',
                  headsign: 'Örs vezér tere',
                  realTime: true,
                  interlineWithPreviousLeg: false,
                  tripId: 'BKK_TRIP_M2_1',
                  serviceDate: '20261110',
                  transitLeg: true,
                  wheelchairAccessible: true,
                  from: {
                    name: 'Deák Ferenc tér M2',
                    lat: 47.497593,
                    lon: 19.053857,
                    stopId: 'BKK_F00961',
                    stopCode: 'F00961',
                    platformCode: '2',
                    arrival: midMs,
                    departure: midMs,
                    stopIndex: 4,
                    stopSequence: 5,
                    requiresFirstDoorBoarding: false // extra non-point property
                  },
                  to: {
                    name: 'Keleti pályaudvar M2',
                    lat: 47.499683,
                    lon: 19.082881,
                    stopId: 'BKK_F01336',
                    stopCode: 'F01336',
                    platformCode: '1',
                    arrival: endMs,
                    departure: endMs,
                    stopIndex: 7,
                    stopSequence: 8
                  },
                  intermediateStops: [
                    {
                      name: 'Astoria M',
                      lat: 47.4944,
                      lon: 19.0601,
                      stopId: 'BKK_F01000',
                      stopCode: 'F01000',
                      platformCode: '1',
                      arrival: midMs + 100000,
                      departure: midMs + 100000,
                      stopIndex: 5,
                      stopSequence: 6,
                      extraUnusedProp: 'strip_me'
                    },
                    {
                      name: 'Blaha Lujza tér M',
                      lat: 47.4965,
                      lon: 19.0702,
                      stopId: 'BKK_F01100',
                      stopCode: 'F01100',
                      platformCode: '1',
                      arrival: midMs + 200000,
                      departure: midMs + 200000,
                      stopIndex: 6,
                      stopSequence: 7
                    }
                  ],
                  // Unused leg-level properties
                  legGeometry: { points: 'w~j`Hqq`bBm@oAc@}A' },
                  elevation: [[0, 80], [2800, 85]],
                  steps: [],
                  patternTripIds: ['BKK_TRIP_M2_1', 'BKK_TRIP_M2_2', 'BKK_TRIP_M2_3']
                }
              ]
            }
          ]
        }
      }
    }
  };
}

test('compact trip planning and mobile payload reduction', async t => {
  await t.test('compactPlan helper preserves all Android parser required fields and strips unused bloat', () => {
    const full = createFullPlanFixture();
    const compacted = compactPlan(full);

    // Root status and metadata intact
    assert.equal(compacted.status, 'OK');
    assert.equal(compacted.code, 200);

    // References stripped to empty object
    assert.deepEqual(compacted.data.references, {});

    const itineraries = compacted.data.entry.plan.itineraries;
    assert.equal(itineraries.length, 1);
    const itin = itineraries[0];

    // Android parser & UI itinerary fields
    assert.equal(itin.duration, 400);
    assert.equal(itin.startTime, 1794313600000);
    assert.equal(itin.endTime, 1794314000000);
    assert.equal(itin.walkTime, 60);
    assert.equal(itin.transitTime, 300);
    assert.equal(itin.waitingTime, 40);
    assert.equal(itin.walkDistance, 80.5);
    assert.equal(itin.transfers, 0);
    assert.equal(itin.generalizedCost, 520);

    // Unused itinerary-level bloat omitted
    assert.equal(itin.bikeTime, undefined);
    assert.equal(itin.elevationLost, undefined);
    assert.equal(itin.patternItineraries, undefined);

    // Legs
    assert.equal(itin.legs.length, 2);

    // Leg 1: WALK
    const walkLeg = itin.legs[0];
    assert.equal(walkLeg.mode, 'WALK');
    assert.equal(walkLeg.startTime, 1794313600000);
    assert.equal(walkLeg.endTime, 1794313700000);
    assert.equal(walkLeg.distance, 80.5);
    assert.equal(walkLeg.realTime, false);
    assert.equal(walkLeg.interlineWithPreviousLeg, false);
    assert.equal(walkLeg.legGeometry, undefined);
    assert.equal(walkLeg.elevation, undefined);
    assert.equal(walkLeg.steps, undefined);

    // Leg 2: SUBWAY (Transit)
    const subwayLeg = itin.legs[1];
    assert.equal(subwayLeg.mode, 'SUBWAY');
    assert.equal(subwayLeg.startTime, 1794313700000);
    assert.equal(subwayLeg.endTime, 1794314000000);
    assert.equal(subwayLeg.distance, 2800.0);
    assert.equal(subwayLeg.routeId, 'BKK_5200');
    assert.equal(subwayLeg.routeShortName, 'M2');
    assert.equal(subwayLeg.routeLongName, 'Metro 2');
    assert.equal(subwayLeg.routeColor, 'E41F18');
    assert.equal(subwayLeg.routeTextColor, 'FFFFFF');
    assert.equal(subwayLeg.headsign, 'Örs vezér tere');
    assert.equal(subwayLeg.realTime, true);
    assert.equal(subwayLeg.interlineWithPreviousLeg, false);
    assert.equal(subwayLeg.tripId, 'BKK_TRIP_M2_1');
    assert.equal(subwayLeg.serviceDate, '20261110');
    assert.equal(subwayLeg.transitLeg, true);
    assert.equal(subwayLeg.wheelchairAccessible, true);

    // Unused bloat omitted on transit leg
    assert.equal(subwayLeg.legGeometry, undefined);
    assert.equal(subwayLeg.elevation, undefined);
    assert.equal(subwayLeg.steps, undefined);
    assert.equal(subwayLeg.patternTripIds, undefined);

    // Endpoints: from and to
    assert.deepEqual(subwayLeg.from, {
      name: 'Deák Ferenc tér M2',
      lat: 47.497593,
      lon: 19.053857,
      stopId: 'BKK_F00961',
      stopCode: 'F00961',
      platformCode: '2',
      arrival: 1794313700000,
      departure: 1794313700000,
      stopIndex: 4,
      stopSequence: 5
    });
    assert.equal(subwayLeg.from.requiresFirstDoorBoarding, undefined);

    assert.deepEqual(subwayLeg.to, {
      name: 'Keleti pályaudvar M2',
      lat: 47.499683,
      lon: 19.082881,
      stopId: 'BKK_F01336',
      stopCode: 'F01336',
      platformCode: '1',
      arrival: 1794314000000,
      departure: 1794314000000,
      stopIndex: 7,
      stopSequence: 8
    });

    // Intermediate stops preserved with all point fields
    assert.equal(subwayLeg.intermediateStops.length, 2);
    assert.deepEqual(subwayLeg.intermediateStops[0], {
      name: 'Astoria M',
      lat: 47.4944,
      lon: 19.0601,
      stopId: 'BKK_F01000',
      stopCode: 'F01000',
      platformCode: '1',
      arrival: 1794313800000,
      departure: 1794313800000,
      stopIndex: 5,
      stopSequence: 6
    });
    assert.equal(subwayLeg.intermediateStops[0].extraUnusedProp, undefined);
    assert.equal(subwayLeg.intermediateStops[1].name, 'Blaha Lujza tér M');
  });

  const liveFixturePath = '/private/tmp/bkk-plan-live.json';
  const hasLiveFixture = fs.existsSync(liveFixturePath);
  await t.test(
    'live fixture /private/tmp/bkk-plan-live.json verified with compactPlan',
    { skip: hasLiveFixture ? false : 'Optional live fixture /private/tmp/bkk-plan-live.json not present on host' },
    () => {
      const raw = JSON.parse(fs.readFileSync(liveFixturePath, 'utf8'));

    // Augment with synthetic intermediateStops to verify they survive compaction
    const transitLeg = raw.data?.entry?.plan?.itineraries?.[0]?.legs?.find(l => l.mode !== 'WALK');
    if (transitLeg) {
      transitLeg.intermediateStops = [
        {
          name: 'Astoria M',
          lat: 47.4944,
          lon: 19.0601,
          stopId: 'BKK_F01000',
          stopCode: 'F01000',
          platformCode: '1',
          arrival: 1794313800000,
          departure: 1794313800000,
          stopIndex: 5,
          stopSequence: 6,
          unusedStepData: { detail: 'strip me' }
        }
      ];
    }

    const compacted = compactPlan(raw);
    const rawSize = JSON.stringify(raw).length;
    const compactSize = JSON.stringify(compacted).length;

    // >95% reduction
    const reductionPercent = ((rawSize - compactSize) / rawSize) * 100;
    assert(reductionPercent > 95, `Expected >95% size reduction, got ${reductionPercent.toFixed(1)}%`);

    // Verify references stripped
    assert.deepEqual(compacted.data.references, {});

    // Verify first transit leg
    const cTransit = compacted.data.entry.plan.itineraries[0].legs.find(l => l.mode !== 'WALK');
    assert(cTransit, 'Transit leg must exist');
    assert.equal(cTransit.legGeometry, undefined);
    assert.equal(cTransit.steps, undefined);
    assert(cTransit.routeId.startsWith('BKK_'));
    assert.equal(cTransit.intermediateStops.length, 1);
    assert.equal(cTransit.intermediateStops[0].name, 'Astoria M');
    assert.equal(cTransit.intermediateStops[0].stopId, 'BKK_F01000');
    assert.equal(cTransit.intermediateStops[0].unusedStepData, undefined);
  });

  await t.test('worker end-to-end: compact=true vs compact=false / default behavior', async () => {
    const originalFetch = globalThis.fetch, originalCaches = globalThis.caches;
    const stored = new Map(), pending = [], upstreamCalls = [];
    const ctx = { waitUntil: p => pending.push(p) };
    const fixture = createFullPlanFixture();

    globalThis.fetch = async (url, options) => {
      upstreamCalls.push({ url: new URL(url), options });
      return Response.json(fixture, { status: 200 });
    };
    globalThis.caches = {
      default: {
        match: async key => stored.get(key.url)?.clone(),
        put: async (key, value) => { stored.set(key.url, value.clone()); }
      }
    };

    const call = (path, env = makeEnv(), init = {}) =>
      worker.fetch(new Request('https://proxy.example' + path, init), env, ctx);

    try {
      // 1. Compact request: compact=true
      const compactRes = await call(basePlanQuery + '&compact=true');
      assert.equal(compactRes.status, 200);
      const compactData = await compactRes.json();
      assert.deepEqual(compactData.data.references, {});
      const cLeg = compactData.data.entry.plan.itineraries[0].legs[1];
      assert.equal(cLeg.legGeometry, undefined);
      assert.equal(cLeg.steps, undefined);
      assert.equal(cLeg.routeId, 'BKK_5200');

      // Verify compact was NOT forwarded to upstream BKK
      assert.equal(upstreamCalls.length, 1);
      const upstream1 = upstreamCalls[0].url;
      assert.equal(upstream1.searchParams.has('compact'), false, 'Upstream must NOT contain compact param');
      assert.equal(upstream1.searchParams.get('key'), secret);

      // 2. Default request: no compact parameter
      const defaultRes = await call(basePlanQuery);
      assert.equal(defaultRes.status, 200);
      const defaultData = await defaultRes.json();
      // References and legGeometry intact
      assert(Object.keys(defaultData.data.references.routes).length > 0);
      const dLeg = defaultData.data.entry.plan.itineraries[0].legs[1];
      assert(dLeg.legGeometry !== undefined);
      assert.equal(dLeg.legGeometry.points, 'w~j`Hqq`bBm@oAc@}A');

      // 3. Explicit compact=false request
      const falseRes = await call(basePlanQuery + '&compact=false');
      assert.equal(falseRes.status, 200);
      const falseData = await falseRes.json();
      assert(Object.keys(falseData.data.references.routes).length > 0);
      const fLeg = falseData.data.entry.plan.itineraries[0].legs[1];
      assert(fLeg.legGeometry !== undefined);

      // Verify upstream call count and none received compact
      assert.equal(upstreamCalls.length, 3);
      for (const callInfo of upstreamCalls) {
        assert.equal(callInfo.url.searchParams.has('compact'), false);
      }
    } finally {
      globalThis.fetch = originalFetch;
      globalThis.caches = originalCaches;
    }
  });

  await t.test('validation rejects invalid compact values and unsupported endpoints', async () => {
    const originalFetch = globalThis.fetch, originalCaches = globalThis.caches;
    const ctx = { waitUntil: () => {} };
    const call = (path) => worker.fetch(new Request('https://proxy.example' + path), makeEnv(), ctx);

    try {
      // Invalid boolean values on plan-trip
      for (const invalidVal of ['1', '0', 'yes', 'no', 'COMPACT', 'TRUE', 'falsey']) {
        const res = await call(basePlanQuery + '&compact=' + invalidVal);
        assert.equal(res.status, 400, `compact=${invalidVal} should be rejected with 400`);
        const json = await res.json();
        assert(json.text.includes('Invalid boolean'));
      }

      // Compact on endpoints that do NOT support compact parameter
      const nonPlanEndpoints = [
        '/api/search.json?query=Deak&compact=true',
        '/api/vehicles-for-location.json?lat=47.5&lon=19.04&radius=500&compact=true',
        '/api/stops-for-location.json?lat=47.5&lon=19.04&radius=500&compact=true',
        '/api/arrivals-and-departures-for-stop.json?stopId=BKK_F01020&compact=true',
        '/api/trip-details.json?tripId=BKK_TRIP1&date=20261110&compact=true',
        '/api/vehicles.json?vehicleId=BKK_1234&compact=true'
      ];

      for (const path of nonPlanEndpoints) {
        const res = await call(path);
        assert.equal(res.status, 400, `compact must be rejected on ${path}`);
        const json = await res.json();
        assert.equal(json.text, 'Unknown or repeated parameter: compact');
      }
    } finally {
      globalThis.fetch = originalFetch;
      globalThis.caches = originalCaches;
    }
  });

  await t.test('handles error envelopes and no-plan responses gracefully without crashing or caching', async () => {
    const originalFetch = globalThis.fetch, originalCaches = globalThis.caches;
    const stored = new Map(), pending = [];
    const ctx = { waitUntil: p => pending.push(p) };
    let mockPayload = {};

    globalThis.fetch = async () => Response.json(mockPayload, { status: 200 });
    globalThis.caches = {
      default: {
        match: async key => stored.get(key.url)?.clone(),
        put: async (key, value) => { stored.set(key.url, value.clone()); }
      }
    };

    const call = (path) => worker.fetch(new Request('https://proxy.example' + path), makeEnv(), ctx);

    try {
      // 1. Upstream returns error inside entry (e.g. OTP router error)
      mockPayload = {
        status: 'OK',
        code: 200,
        data: {
          entry: {
            error: { id: 404, msg: 'No path found' },
            plan: null
          }
        }
      };

      const errRes = await call(basePlanQuery + '&compact=true');
      assert.equal(errRes.status, 200);
      const errData = await errRes.json();
      assert.equal(errData.data.entry.error.msg, 'No path found');

      await Promise.all(pending);
      // Ensure error response is NOT cached
      assert.equal(stored.size, 0, 'Router errors must not be cached');

      // 2. Empty itineraries list
      mockPayload = {
        status: 'OK',
        code: 200,
        data: {
          entry: {
            plan: { itineraries: [] }
          }
        }
      };
      const emptyRes = await call(basePlanQuery + '&compact=true');
      assert.equal(emptyRes.status, 200);
      const emptyData = await emptyRes.json();
      assert.deepEqual(emptyData.data.entry.plan.itineraries, []);
      await Promise.all(pending);
      assert.equal(stored.size, 1, 'Valid empty itinerary plan is cached');
    } finally {
      globalThis.fetch = originalFetch;
      globalThis.caches = originalCaches;
    }
  });

  await t.test('deep secret echoes in plan itineraries/legs are redacted in compact mode', async () => {
    const originalFetch = globalThis.fetch, originalCaches = globalThis.caches;
    const ctx = { waitUntil: () => {} };
    const fixture = createFullPlanFixture();

    // Inject secret in requestParameters, in headsign, and deep inside from/to
    fixture.data.entry.requestParameters.key = secret;
    fixture.data.entry.requestParameters.deepObject = { token: secret };
    fixture.data.entry.plan.itineraries[0].legs[1].headsign = 'To ' + secret;
    fixture.data.entry.plan.itineraries[0].legs[1].from.name = 'Echo ' + secret;

    globalThis.fetch = async () => Response.json(fixture, { status: 200 });
    globalThis.caches = {
      default: {
        match: async () => null,
        put: async () => {}
      }
    };

    const call = (path) => worker.fetch(new Request('https://proxy.example' + path), makeEnv(), ctx);

    try {
      const res = await call(basePlanQuery + '&compact=true');
      assert.equal(res.status, 200);
      const text = await res.text();

      assert(!text.includes(secret), 'Response must NOT contain raw secret');
      assert(!text.includes('"key"'), 'Response must NOT have key parameter in objects');

      const data = JSON.parse(text);
      assert.equal(data.data.entry.requestParameters.key, undefined);
      assert.equal(data.data.entry.plan.itineraries[0].legs[1].headsign, 'To [redacted]');
      assert.equal(data.data.entry.plan.itineraries[0].legs[1].from.name, 'Echo [redacted]');
    } finally {
      globalThis.fetch = originalFetch;
      globalThis.caches = originalCaches;
    }
  });

  await t.test('cache separation and 5s TTL guarantees full and compact responses never mix', async () => {
    const originalFetch = globalThis.fetch, originalCaches = globalThis.caches;
    const stored = new Map(), pending = [], upstreamCalls = [];
    const ctx = { waitUntil: p => pending.push(p) };
    const fixture = createFullPlanFixture();

    globalThis.fetch = async (url, options) => {
      upstreamCalls.push({ url: new URL(url), options });
      return Response.json(fixture, { status: 200 });
    };
    globalThis.caches = {
      default: {
        match: async key => stored.get(key.url)?.clone(),
        put: async (key, value) => { stored.set(key.url, value.clone()); }
      }
    };

    const call = (path) => worker.fetch(new Request('https://proxy.example' + path), makeEnv(), ctx);

    try {
      // 1. Initial compact query: MISS -> put in cache
      const compact1 = await call(basePlanQuery + '&compact=true');
      assert.equal(compact1.headers.get('X-Proxy-Cache'), 'MISS');
      await Promise.all(pending);
      assert.equal(stored.size, 1);

      // Verify cached entry has 5s max-age
      const compactCacheEntry = [...stored.values()][0];
      assert.equal(compactCacheEntry.headers.get('Cache-Control'), 'public, max-age=5');

      // 2. Second compact query: HIT
      const compact2 = await call(basePlanQuery + '&compact=true');
      assert.equal(compact2.headers.get('X-Proxy-Cache'), 'HIT');
      const c2Data = await compact2.json();
      assert.deepEqual(c2Data.data.references, {});
      assert.equal(upstreamCalls.length, 1);

      // 3. Full query (default, no compact): must be a MISS and not mix with compact!
      const full1 = await call(basePlanQuery);
      assert.equal(full1.headers.get('X-Proxy-Cache'), 'MISS');
      await Promise.all(pending);
      assert.equal(stored.size, 2);
      assert.equal(upstreamCalls.length, 2);

      const f1Data = await full1.json();
      assert(Object.keys(f1Data.data.references.routes).length > 0, 'Full response must keep references');

      // 4. Second full query: HIT
      const full2 = await call(basePlanQuery);
      assert.equal(full2.headers.get('X-Proxy-Cache'), 'HIT');
      assert.equal(upstreamCalls.length, 2);

      // 5. Explicit compact=false query: separate key, MISS, then HIT
      const false1 = await call(basePlanQuery + '&compact=false');
      assert.equal(false1.headers.get('X-Proxy-Cache'), 'MISS');
      await Promise.all(pending);
      assert.equal(stored.size, 3);
      assert.equal(upstreamCalls.length, 3);

      const false2 = await call(basePlanQuery + '&compact=false');
      assert.equal(false2.headers.get('X-Proxy-Cache'), 'HIT');
      assert.equal(upstreamCalls.length, 3);

      // Verify stored URLs are distinct and none contain secrets
      const cachedUrls = [...stored.keys()];
      assert(cachedUrls.some(u => u.includes('compact=true')));
      assert(cachedUrls.some(u => u.includes('compact=false')));
      assert(cachedUrls.some(u => !u.includes('compact')));
      for (const u of cachedUrls) {
        assert(!u.includes(secret));
        assert(!u.includes('key='));
      }
    } finally {
      globalThis.fetch = originalFetch;
      globalThis.caches = originalCaches;
    }
  });
});
