import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  mergeVehicles,
  mergeStops,
  mapVehicle,
  mapStop,
  inArea,
  distance,
  normalize
} from './emma.js';
import worker from './index.js';

const secret = 'super-secret-bkk-token-12345';
const now = Math.floor(Date.now() / 1000);

const makeEnv = () => ({
  BKK_API_KEY: secret,
  ALLOWED_ORIGINS: '*',
  REQUEST_LIMITER: { limit: async () => ({ success: true }) },
  PLAN_LIMITER: { limit: async () => ({ success: true }) }
});

const railRoute = {
  gtfsId: '1:R_TRAIN',
  shortName: 'S30',
  longName: 'Budapest-Déli - Székesfehérvár',
  mode: 'RAIL',
  color: '1666A8'
};

const emptyRefs = () => ({ routes: {}, stops: {}, trips: {}, vehicles: {} });

test('BKK-primary identity preservation and vehicle merging', async t => {
  await t.test('BKK primary vehicle wins over EMMA fallback and preserves all BKK fields', () => {
    const primary = {
      vehicleId: 'BKK_945514150149',
      location: { lat: 47.501, lon: 19.081 },
      routeId: 'BKK_S30',
      label: 'BKK Label',
      source: 'bkk'
    };
    const fallback = {
      vehicleId: 'BKK_945514150149',
      location: { lat: 47.502, lon: 19.082 },
      routeId: 'emma:1:R_TRAIN',
      label: 'EMMA Label',
      source: 'emma'
    };
    const merged = mergeVehicles([primary], [fallback]);
    assert.equal(merged.length, 1);
    assert.equal(merged[0], primary);
    assert.equal(merged[0].source, 'bkk');
    assert.equal(merged[0].routeId, 'BKK_S30');
    assert.deepEqual(merged[0].location, { lat: 47.501, lon: 19.081 });
  });

  await t.test('Coupled vehicle sets match individual components and avoid duplicate markers', () => {
    // BKK coupled Flirt/KISS sets appear as hyphenated vehicleId
    const coupled = {
      vehicleId: 'BKK_415014-415015',
      location: { lat: 47.50, lon: 19.08 }
    };
    const emmaComponent1 = {
      vehicleId: 'BKK_415014',
      location: { lat: 47.50, lon: 19.08 },
      source: 'emma'
    };
    const emmaComponent2 = {
      vehicleId: 'BKK_415015',
      location: { lat: 47.50, lon: 19.08 },
      source: 'emma'
    };
    const emmaDifferent = {
      vehicleId: 'BKK_415016',
      location: { lat: 47.50, lon: 19.08 },
      source: 'emma'
    };

    // Neither component 1 nor 2 should be added if coupled set is present
    const merged = mergeVehicles([coupled], [emmaComponent1, emmaComponent2, emmaDifferent]);
    assert.equal(merged.length, 2);
    assert.equal(merged[0], coupled);
    assert.equal(merged[1].vehicleId, 'BKK_415016');
  });

  await t.test('Distinct separate trains standing at identical coordinates are NOT collapsed', () => {
    const trainA = {
      vehicleId: 'BKK_94551415001',
      location: { lat: 47.5002, lon: 19.0841 },
      source: 'emma'
    };
    const trainB = {
      vehicleId: 'BKK_94551415002',
      location: { lat: 47.5002, lon: 19.0841 },
      source: 'emma'
    };
    const merged = mergeVehicles([], [trainA, trainB]);
    assert.equal(merged.length, 2);
    assert.equal(merged[0].vehicleId, 'BKK_94551415001');
    assert.equal(merged[1].vehicleId, 'BKK_94551415002');
  });
});

test('EMMA rail-only supplementation and invalid/stale coordinates filtering', async t => {
  await t.test('Strictly rejects non-rail modes from EMMA', () => {
    const nonRailModes = ['BUS', 'TRAM', 'SUBURBAN_RAILWAY', 'FERRY', 'CABLE_CAR', 'GONDOLA', 'FUNICULAR'];
    for (const mode of nonRailModes) {
      const v = {
        vehicleId: '1:12345',
        lat: 47.5,
        lon: 19.08,
        lastUpdated: now,
        trip: { route: { ...railRoute, mode } }
      };
      assert.equal(mapVehicle(v, emptyRefs(), now), null, `Mode ${mode} must be rejected`);

      const s = {
        gtfsId: '1:STOP_1',
        name: 'Test Stop',
        lat: 47.5,
        lon: 19.08,
        vehicleMode: mode
      };
      assert.equal(mapStop(s), null, `Stop mode ${mode} must be rejected`);
    }

    // Accepts RAIL and TRAMTRAIN
    const railV = {
      vehicleId: '1:12345',
      lat: 47.5,
      lon: 19.08,
      lastUpdated: now,
      trip: { route: { ...railRoute, mode: 'RAIL' } }
    };
    assert.notEqual(mapVehicle(railV, emptyRefs(), now), null);

    const tramTrainV = {
      vehicleId: '1:12346',
      lat: 47.5,
      lon: 19.08,
      lastUpdated: now,
      trip: { route: { ...railRoute, mode: 'TRAMTRAIN' } }
    };
    assert.notEqual(mapVehicle(tramTrainV, emptyRefs(), now), null);
  });

  await t.test('Filters invalid coordinates and Hungarian geographical boundaries', () => {
    const makePos = (lat, lon) => ({
      vehicleId: '1:12345',
      lat,
      lon,
      lastUpdated: now,
      trip: { route: railRoute }
    });

    // Valid inside Hungary: lat in [45, 49], lon in [16, 23.5]
    assert.notEqual(mapVehicle(makePos(47.5, 19.08), emptyRefs(), now), null);

    // Boundary violations
    assert.equal(mapVehicle(makePos(44.99, 19.08), emptyRefs(), now), null);
    assert.equal(mapVehicle(makePos(49.01, 19.08), emptyRefs(), now), null);
    assert.equal(mapVehicle(makePos(47.5, 15.99), emptyRefs(), now), null);
    assert.equal(mapVehicle(makePos(47.5, 23.51), emptyRefs(), now), null);

    // Non-finite values
    assert.equal(mapVehicle(makePos(NaN, 19.08), emptyRefs(), now), null);
    assert.equal(mapVehicle(makePos(47.5, Infinity), emptyRefs(), now), null);
    assert.equal(mapVehicle(makePos('47.5', 19.08), emptyRefs(), now), null);
    assert.equal(mapVehicle(makePos(null, 19.08), emptyRefs(), now), null);
    assert.equal(mapVehicle(makePos(undefined, 19.08), emptyRefs(), now), null);
  });

  await t.test('Filters stale positions (>5 min) and excessive future timestamps (>60s)', () => {
    const makeTime = (tOffset) => ({
      vehicleId: '1:12345',
      lat: 47.5,
      lon: 19.08,
      lastUpdated: now + tOffset,
      trip: { route: railRoute }
    });

    // Fresh
    assert.notEqual(mapVehicle(makeTime(0), emptyRefs(), now), null);
    assert.notEqual(mapVehicle(makeTime(-299), emptyRefs(), now), null);
    assert.notEqual(mapVehicle(makeTime(59), emptyRefs(), now), null);

    // Stale: older than 300s
    assert.equal(mapVehicle(makeTime(-301), emptyRefs(), now), null);
    assert.equal(mapVehicle(makeTime(-600), emptyRefs(), now), null);

    // Future drift: > 60s ahead
    assert.equal(mapVehicle(makeTime(61), emptyRefs(), now), null);
  });
});

test('Railway stop deduplication and platform grouping rules', async t => {
  await t.test('EMMA platforms sharing one parent station code collapse into one station', () => {
    const parent = { gtfsId: '1:005510017' };
    const platform1 = mapStop({
      gtfsId: '1:005510017_1',
      name: 'Budapest-Keleti vágány 1',
      lat: 47.5002,
      lon: 19.0841,
      vehicleMode: 'RAIL',
      parentStation: parent
    });
    const platform2 = mapStop({
      gtfsId: '1:005510017_2',
      name: 'Budapest-Keleti vágány 2',
      lat: 47.5003,
      lon: 19.0842,
      vehicleMode: 'RAIL',
      parentStation: parent
    });
    const merged = mergeStops([], [platform1, platform2]);
    assert.equal(merged.length, 1);
    assert.equal(merged[0].id, 'emma:1:005510017_1');
  });

  await t.test('Railway station deduplication does NOT collapse nearby bus or metro stops', () => {
    const railStation = {
      id: 'BKK_005510017_0',
      parentStationId: 'BKK_005510017',
      name: 'Budapest-Keleti',
      lat: 47.5002,
      lon: 19.0838,
      type: 'RAIL',
      locationType: 0
    };
    const busStop = {
      id: 'BKK_F01234',
      name: 'Keleti pályaudvar M',
      lat: 47.5004,
      lon: 19.0839, // Within 30m of railStation
      type: 'BUS',
      locationType: 0
    };
    const metroStop = {
      id: 'BKK_M01234',
      name: 'Keleti pályaudvar',
      lat: 47.5001,
      lon: 19.0837,
      type: 'SUBWAY',
      locationType: 0
    };
    const emmaRailStop = mapStop({
      gtfsId: '1:005510017_1',
      name: 'Budapest-Keleti',
      lat: 47.50025,
      lon: 19.0840,
      vehicleMode: 'RAIL',
      parentStation: { gtfsId: '1:005510017' }
    });

    const merged = mergeStops([railStation, busStop, metroStop], [emmaRailStop]);
    // Rail stop deduped into railStation; bus and metro stops remain intact!
    assert.equal(merged.length, 3);
    assert(merged.includes(railStation));
    assert(merged.includes(busStop));
    assert(merged.includes(metroStop));
    assert(!merged.includes(emmaRailStop));
  });

  await t.test('Distant railway stations with same name (>150m) remain distinct', () => {
    const stationA = {
      id: 'BKK_005500001',
      name: 'Kelenföld',
      lat: 47.465,
      lon: 19.020,
      type: 'RAIL'
    };
    const stationB = mapStop({
      gtfsId: '1:different_station',
      name: 'Kelenföld',
      lat: 47.470, // ~550m away
      lon: 19.020,
      vehicleMode: 'RAIL'
    });
    const merged = mergeStops([stationA], [stationB]);
    assert.equal(merged.length, 2);
    assert.equal(merged[0].id, 'BKK_005500001');
    assert.equal(merged[1].id, 'emma:1:different_station');
  });
});

test('Secret redaction and security boundaries', async t => {
  const originalFetch = globalThis.fetch;
  const originalCaches = globalThis.caches;
  const stored = new Map();
  const pending = [];
  const ctx = { waitUntil: p => pending.push(p) };

  globalThis.caches = {
    default: {
      match: async key => stored.get(key.url)?.clone(),
      put: async (key, val) => { stored.set(key.url, val.clone()); }
    }
  };

  const call = (path, env = makeEnv(), init = {}) =>
    worker.fetch(new Request('https://proxy.example' + path, init), env, ctx);

  try {
    await t.test('Echoed BKK secret in requestParameters, nested objects, and strings is thoroughly redacted', async () => {
      stored.clear();
      globalThis.fetch = async () => Response.json({
        status: 'OK',
        data: {
          entry: {
            requestParameters: {
              key: secret,
              ApiKey: secret,
              api_key: secret,
              lat: '47.5',
              lon: '19.04'
            },
            notes: [
              `Debug note with key=${secret} exposed`,
              { nestedApiKey: secret, cleanField: 'ok' }
            ],
            deep: {
              level1: {
                level2: {
                  key: secret,
                  tokenText: `bearer ${secret}`
                }
              }
            }
          }
        }
      });

      const res = await call('/api/vehicles-for-location.json?lat=47.5&lon=19.04&latSpan=0.01&lonSpan=0.01');
      assert.equal(res.status, 200);
      const body = await res.text();
      assert(!body.includes(secret), 'Secret token must never appear in response body');
      assert(!body.includes('"key":'), 'key field must be removed');
      assert(!body.includes('"apikey":'), 'apikey field must be removed');
      assert(!body.includes('"api_key":'), 'api_key field must be removed');
      assert(body.includes('[redacted]'), 'String secrets must be replaced with [redacted]');

      const json = JSON.parse(body);
      assert.equal(json.data.entry.requestParameters.key, undefined);
      assert.equal(json.data.entry.deep.level1.level2.tokenText, 'bearer [redacted]');
    });

    await t.test('Rejects HTTP 3xx redirects with 502 to prevent credential forwarding', async () => {
      stored.clear();
      for (const redirectStatus of [301, 302, 307, 308]) {
        globalThis.fetch = async () => new Response(null, {
          status: redirectStatus,
          headers: { Location: 'https://attacker.example/steal?key=' + secret }
        });
        const res = await call('/api/vehicles-for-location.json?lat=47.51&lon=19.05&latSpan=0.01&lonSpan=0.01');
        assert.equal(res.status, 502);
        const text = await res.text();
        assert(!text.includes(secret));
        assert(!text.includes('attacker.example'));
      }
    });

    await t.test('Fetch exception containing keyed URL never leaks to client', async () => {
      stored.clear();
      globalThis.fetch = async (url) => {
        throw new TypeError(`Fetch failed for URL: ${url}`);
      };
      const res = await call('/api/vehicles-for-location.json?lat=47.52&lon=19.06&latSpan=0.01&lonSpan=0.01');
      assert.equal(res.status, 502);
      const text = await res.text();
      assert(!text.includes(secret));
      assert(!text.includes('futar.bkk.hu'));
      const json = JSON.parse(text);
      assert.equal(json.text, 'Could not reach BKK. Try again shortly.');
    });

    await t.test('Cache keys never contain secrets or client identifiers', async () => {
      stored.clear();
      globalThis.fetch = async (url) => {
        if (String(url).includes('mavplusz.hu')) return Response.json({ data: { vehiclePositions: [] } });
        return Response.json({ status: 'OK', data: { list: [] } });
      };
      await call('/api/vehicles-for-location.json?lat=47.53&lon=19.07&latSpan=0.01&lonSpan=0.01');
      await Promise.all(pending);

      const cacheKeys = [...stored.keys()].filter(k => k.includes('/cache-v'));
      assert(cacheKeys.length >= 1, 'Expected at least one /cache-v* key');
      for (const cacheUrl of cacheKeys) {
        assert(!cacheUrl.includes(secret));
        assert(!cacheUrl.includes('key='));
        assert(!cacheUrl.includes('local')); // Client IP
        assert(cacheUrl.includes('/cache-v'));
      }
    });
  } finally {
    globalThis.fetch = originalFetch;
    globalThis.caches = originalCaches;
  }
});

test('EMMA negative-cache and cooldown prevents retry storms', async () => {
  const originalFetch = globalThis.fetch;
  const originalCaches = globalThis.caches;
  const stored = new Map();
  const pending = [];
  const ctx = { waitUntil: p => pending.push(p) };

  globalThis.caches = {
    default: {
      match: async key => stored.get(key.url)?.clone(),
      put: async (key, val) => { stored.set(key.url, val.clone()); }
    }
  };

  let emmaFetchCalls = 0;
  let bkkFetchCalls = 0;

  globalThis.fetch = async (url, options) => {
    if (String(url).includes('mavplusz.hu')) {
      emmaFetchCalls++;
      return new Response('Cloudflare 403 Forbidden', {
        status: 403,
        headers: { 'Server': 'cloudflare' }
      });
    }
    bkkFetchCalls++;
    return Response.json({
      status: 'OK',
      data: { list: [{ vehicleId: 'BKK_101', location: { lat: 47.5, lon: 19.04 } }] }
    });
  };

  const call = (path) =>
    worker.fetch(new Request('https://proxy.example' + path), makeEnv(), ctx);

  try {
    // First call: EMMA fails with 403, negative cache / cooldown recorded
    const res1 = await call('/api/vehicles-for-location.json?lat=47.45&lon=19.02&latSpan=0.01&lonSpan=0.01');
    assert.equal(res1.status, 200);
    const json1 = await res1.json();
    assert.equal(json1.fallback?.status, 'unavailable');
    assert.equal(emmaFetchCalls, 1);
    await Promise.all(pending);

    // Negative cache entry must be recorded in cache
    const emmaNegativeKey = [...stored.keys()].find(k => k.includes('/emma-cache-v1/'));
    assert(emmaNegativeKey, 'EMMA negative cache entry must be stored');
    const negativeEntry = await stored.get(emmaNegativeKey).json();
    assert.equal(negativeEntry.__emmaUnavailable, true);

    // Second call to different viewport (different BKK cacheKey, but same EMMA nationwide vehiclePositions query)
    const res2 = await call('/api/vehicles-for-location.json?lat=47.55&lon=19.12&latSpan=0.01&lonSpan=0.01');
    assert.equal(res2.status, 200);
    const json2 = await res2.json();
    assert.equal(json2.fallback?.status, 'unavailable');

    // EMMA fetch count must STILL be 1 because negative cache / cooldown prevented network retry storm!
    assert.equal(emmaFetchCalls, 1, 'EMMA fetch count must not increase during cooldown');
  } finally {
    globalThis.fetch = originalFetch;
    globalThis.caches = originalCaches;
  }
});

test('Parameter validation and abuse boundary regression checks', async () => {
  const env = makeEnv();
  // Provide safe fallback mock so any valid request does not crash with unmocked network fetch
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => Response.json({ status: 'OK', data: { entry: {}, list: [] } });

  const call = (path, init = {}) =>
    worker.fetch(new Request('https://proxy.example' + path, init), env, { waitUntil: () => {} });

  try {
    const invalidQueries = [
      // Non-existent endpoint
      ['/api/nonexistent.json', 404],
      // Control characters in parameter
      ['/api/search.json?query=Keleti%00', 400],
      ['/api/search.json?query=Keleti%1f', 400],
      // Disallowed parameter
      ['/api/search.json?query=Keleti&unexpected=bad', 400],
      // Client-supplied key parameter rejected
      ['/api/search.json?query=Keleti&key=customkey', 400],
      ['/api/search.json?query=Keleti&apiKey=customkey', 400],
      // Repeated parameter
      ['/api/search.json?query=Keleti&query=Nyugati', 400],
      // Parameter exceeding length 300
      ['/api/search.json?query=' + 'A'.repeat(301), 400],
      // Missing required parameter
      ['/api/search.json', 400],
      ['/api/vehicles-for-location.json?lat=47.5', 400],
      // Location queries requiring radius OR both latSpan and lonSpan
      ['/api/vehicles-for-location.json?lat=47.5&lon=19.04', 400],
      ['/api/vehicles-for-location.json?lat=47.5&lon=19.04&latSpan=0.02', 400],
      // Out of range coordinates
      ['/api/vehicles-for-location.json?lat=44.9&lon=19.04&radius=1000', 400],
      ['/api/vehicles-for-location.json?lat=49.1&lon=19.04&radius=1000', 400],
      ['/api/vehicles-for-location.json?lat=47.5&lon=15.9&radius=1000', 400],
      ['/api/vehicles-for-location.json?lat=47.5&lon=23.6&radius=1000', 400],
      // Out of range spans (>0.09) and radius (>5000)
      ['/api/vehicles-for-location.json?lat=47.5&lon=19.04&latSpan=0.10&lonSpan=0.05', 400],
      ['/api/vehicles-for-location.json?lat=47.5&lon=19.04&radius=5001', 400],
      // Invalid version
      ['/api/search.json?query=Keleti&version=3', 400],
      // Invalid identifier format or > 10 items
      ['/api/vehicles.json?vehicleId=bad%20id', 400],
      ['/api/vehicles.json?vehicleId=1,2,3,4,5,6,7,8,9,10,11', 400],
      // Invalid date / time format
      ['/api/trip-details.json?tripId=T123&date=2026-11-10', 400], // trip-details expects YYYYMMDD
      ['/api/plan-trip.json?fromPlace=A::47.5,19.04&toPlace=B::47.6,19.1&mode=WALK&date=20261110', 400], // plan-trip expects YYYY-MM-DD
      ['/api/plan-trip.json?fromPlace=A::47.5,19.04&toPlace=B::47.6,19.1&mode=WALK&time=25:00', 400],
      // Places outside Hungary in planner: lat 52.5 > 49, lon 13.4 < 16
      ['/api/plan-trip.json?fromPlace=Berlin::52.5,13.4&toPlace=B::47.6,19.1&mode=WALK', 400],
      // Unsupported travel mode
      ['/api/plan-trip.json?fromPlace=A::47.5,19.04&toPlace=B::47.6,19.1&mode=BICYCLE', 400]
    ];

    for (const [path, expectedStatus] of invalidQueries) {
      const res = await call(path);
      assert.equal(res.status, expectedStatus, `Query ${path} expected ${expectedStatus} but got ${res.status}`);
    }
  } finally {
    globalThis.fetch = originalFetch;
  }
});
