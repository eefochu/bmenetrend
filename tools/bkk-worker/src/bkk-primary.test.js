import { test } from 'node:test';
import assert from 'node:assert/strict';
import worker from './index.js';

const secret = 'test-secret-bkk-key-primary-123';
const makeEnv = () => ({
  BKK_API_KEY: secret,
  ALLOWED_ORIGINS: '*',
  REQUEST_LIMITER: { limit: async () => ({ success: true }) },
  PLAN_LIMITER: { limit: async () => ({ success: true }) }
});

test('BKK primary endpoints and batching regression tests', async t => {
  const originalFetch = globalThis.fetch;
  const originalCaches = globalThis.caches;
  const stored = new Map();
  const pending = [];
  const upstreamCalls = [];
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
    // 1. Exact upstream endpoint + parameters + 300s cache TTL
    await t.test('route-details: exact upstream endpoint, params, secret forwarding, 300s cache TTL', async () => {
      stored.clear();
      upstreamCalls.length = 0;
      globalThis.fetch = async (url, options) => {
        upstreamCalls.push({ url: new URL(url), options });
        return Response.json({
          status: 'OK',
          code: 200,
          data: {
            entry: {
              id: 'BKK_5200',
              shortName: 'M2',
              variants: [{ patternId: 'P1', direction: '0', stopIds: ['BKK_F1', 'BKK_F2'] }]
            },
            references: { routes: { 'BKK_5200': { id: 'BKK_5200' } } }
          }
        });
      };

      const path = '/api/route-details.json?routeId=BKK_5200&date=20261110&related=true';
      const res = await call(path);
      assert.equal(res.status, 200);
      assert.equal(res.headers.get('X-Proxy-Cache'), 'MISS');

      assert.equal(upstreamCalls.length, 1);
      const upstream = upstreamCalls[0];
      assert.equal(upstream.url.origin, 'https://futar.bkk.hu');
      assert.equal(upstream.url.pathname, '/api/query/v1/ws/otp/api/where/route-details.json');
      assert.equal(upstream.url.searchParams.get('routeId'), 'BKK_5200');
      assert.equal(upstream.url.searchParams.get('date'), '20261110');
      assert.equal(upstream.url.searchParams.get('related'), 'true');
      assert.equal(upstream.url.searchParams.get('key'), secret);
      assert.equal(upstream.url.searchParams.get('version'), '4');
      assert.equal(upstream.options.redirect, 'manual');

      await Promise.all(pending);
      const cached = [...stored.values()].at(-1);
      assert(cached, 'Cache should contain route-details response');
      assert.equal(cached.headers.get('Cache-Control'), 'public, max-age=300');

      const hit = await call(path);
      assert.equal(hit.status, 200);
      assert.equal(hit.headers.get('X-Proxy-Cache'), 'HIT');
      assert.equal(upstreamCalls.length, 1);
    });

    await t.test('route-details-for-stop: exact upstream endpoint, params, 300s cache TTL', async () => {
      stored.clear();
      upstreamCalls.length = 0;
      globalThis.fetch = async (url, options) => {
        upstreamCalls.push({ url: new URL(url), options });
        return Response.json({
          status: 'OK',
          code: 200,
          data: {
            list: [{ id: 'BKK_5200', shortName: 'M2' }],
            references: { routes: { 'BKK_5200': { id: 'BKK_5200' } } }
          }
        });
      };

      const path = '/api/route-details-for-stop.json?stopId=BKK_F01037';
      const res = await call(path);
      assert.equal(res.status, 200);
      assert.equal(res.headers.get('X-Proxy-Cache'), 'MISS');

      assert.equal(upstreamCalls.length, 1);
      const upstream = upstreamCalls[0];
      assert.equal(upstream.url.pathname, '/api/query/v1/ws/otp/api/where/route-details-for-stop.json');
      assert.equal(upstream.url.searchParams.get('stopId'), 'BKK_F01037');
      assert.equal(upstream.url.searchParams.get('key'), secret);
      assert.equal(upstream.url.searchParams.get('version'), '4');

      await Promise.all(pending);
      const cached = [...stored.values()].at(-1);
      assert.equal(cached.headers.get('Cache-Control'), 'public, max-age=300');
    });

    await t.test('references: exact upstream endpoint, accepts IDs, rejects empty query, 300s cache', async () => {
      stored.clear();
      upstreamCalls.length = 0;
      globalThis.fetch = async (url, options) => {
        upstreamCalls.push({ url: new URL(url), options });
        return Response.json({
          status: 'OK',
          code: 200,
          data: {
            entry: {},
            references: { stops: { 'BKK_F01037': { id: 'BKK_F01037', name: 'Keleti' } } }
          }
        });
      };

      // Empty references query rejected
      const emptyRes = await call('/api/references.json');
      assert.equal(emptyRes.status, 400);

      // Valid references query
      const path = '/api/references.json?stopId=BKK_F01037&routeId=BKK_5200';
      const res = await call(path);
      assert.equal(res.status, 200);
      assert.equal(upstreamCalls.length, 1);
      const upstream = upstreamCalls[0];
      assert.equal(upstream.url.pathname, '/api/query/v1/ws/otp/api/where/references.json');
      assert.equal(upstream.url.searchParams.get('stopId'), 'BKK_F01037');
      assert.equal(upstream.url.searchParams.get('routeId'), 'BKK_5200');
      assert.equal(upstream.url.searchParams.get('key'), secret);

      await Promise.all(pending);
      const cached = [...stored.values()].at(-1);
      assert.equal(cached.headers.get('Cache-Control'), 'public, max-age=300');
    });

    await t.test('schedule-for-stop: exact upstream endpoint, 8-digit date, 300s cache', async () => {
      stored.clear();
      upstreamCalls.length = 0;
      globalThis.fetch = async (url, options) => {
        upstreamCalls.push({ url: new URL(url), options });
        return Response.json({
          status: 'OK',
          code: 200,
          data: {
            entry: { schedules: [] },
            references: {}
          }
        });
      };

      const path = '/api/schedule-for-stop.json?stopId=BKK_F01037&date=20261110';
      const res = await call(path);
      assert.equal(res.status, 200);
      assert.equal(upstreamCalls.length, 1);
      const upstream = upstreamCalls[0];
      assert.equal(upstream.url.pathname, '/api/query/v1/ws/otp/api/where/schedule-for-stop.json');
      assert.equal(upstream.url.searchParams.get('stopId'), 'BKK_F01037');
      assert.equal(upstream.url.searchParams.get('date'), '20261110');
      assert.equal(upstream.url.searchParams.get('key'), secret);

      await Promise.all(pending);
      const cached = [...stored.values()].at(-1);
      assert.equal(cached.headers.get('Cache-Control'), 'public, max-age=300');
    });

    // 2. Batch boards endpoint: arrivals-and-departures-for-stops
    await t.test('arrivals-and-departures-for-stops: fanout <= 3, dedup, missing stopId populated, 10s cache on full success', async () => {
      stored.clear();
      upstreamCalls.length = 0;
      let activeFetches = 0;
      let maxConcurrent = 0;

      globalThis.fetch = async (url, options) => {
        activeFetches++;
        maxConcurrent = Math.max(maxConcurrent, activeFetches);
        upstreamCalls.push({ url: new URL(url), options });
        // Small delay to test concurrency
        await new Promise(r => setTimeout(r, 10));
        activeFetches--;

        const stopId = new URL(url).searchParams.get('stopId');
        return Response.json({
          status: 'OK',
          code: 200,
          data: {
            entry: {
              // Return a stopTime missing stopId to verify worker populates it
              stopTimes: [{ departureTime: 1794314000, tripId: 'T1' }]
            },
            references: {
              routes: { [`R_${stopId}`]: { id: `R_${stopId}` } }
            }
          }
        });
      };

      // 4 requested IDs including 1 duplicate: BKK_F1, BKK_F2, BKK_F1, BKK_F3 -> unique 3
      const path = '/api/arrivals-and-departures-for-stops.json?stopId=BKK_F1,BKK_F2,BKK_F1,BKK_F3&minutesBefore=5&minutesAfter=30&limit=10';
      const res = await call(path);
      assert.equal(res.status, 200);
      assert.equal(res.headers.get('X-Proxy-Cache'), 'MISS');

      // Deduplication: unique stopIds are BKK_F1, BKK_F2, BKK_F3 (3 calls)
      assert.equal(upstreamCalls.length, 3);
      assert(maxConcurrent <= 3, `Max concurrent fetches was ${maxConcurrent}, expected <= 3`);

      const json = await res.json();
      assert.equal(json.status, 'OK');
      assert.deepEqual(json.data.successfulStopIds, ['BKK_F1', 'BKK_F2', 'BKK_F3']);
      assert.deepEqual(json.data.failedStopIds, []);

      // Verify row.stopId populated from requested stop
      assert.equal(json.data.entry.stopTimes.length, 3);
      assert.equal(json.data.entry.stopTimes[0].stopId, 'BKK_F1');
      assert.equal(json.data.entry.stopTimes[1].stopId, 'BKK_F2');
      assert.equal(json.data.entry.stopTimes[2].stopId, 'BKK_F3');

      // Verify references merged
      assert(json.data.references.routes['R_BKK_F1']);
      assert(json.data.references.routes['R_BKK_F2']);
      assert(json.data.references.routes['R_BKK_F3']);

      // 10s TTL on full success
      await Promise.all(pending);
      const cached = [...stored.values()].at(-1);
      assert(cached, 'Fully successful batch must be cached');
      assert.equal(cached.headers.get('Cache-Control'), 'public, max-age=10');
    });

    await t.test('arrivals-and-departures-for-stops: legitimate empty stopTimes is success', async () => {
      stored.clear();
      upstreamCalls.length = 0;
      globalThis.fetch = async (url) => {
        const stopId = new URL(url).searchParams.get('stopId');
        return Response.json({
          status: 'OK',
          code: 200,
          data: {
            entry: { stopTimes: [] }, // legitimate empty
            references: {}
          }
        });
      };

      const path = '/api/arrivals-and-departures-for-stops.json?stopId=BKK_F1,BKK_F2';
      const res = await call(path);
      assert.equal(res.status, 200);
      const json = await res.json();
      assert.deepEqual(json.data.successfulStopIds, ['BKK_F1', 'BKK_F2']);
      assert.deepEqual(json.data.failedStopIds, []);
      assert.equal(json.data.entry.stopTimes.length, 0);

      // Caching applies because there are 0 failures
      await Promise.all(pending);
      assert.equal(stored.size, 1);
    });

    await t.test('arrivals-and-departures-for-stops: partial failures, emma: IDs, and NO caching on partial failure', async () => {
      stored.clear();
      upstreamCalls.length = 0;
      globalThis.fetch = async (url) => {
        const stopId = new URL(url).searchParams.get('stopId');
        upstreamCalls.push(stopId);
        if (stopId === 'BKK_ERR') {
          return new Response('Internal Server Error with key=' + secret, { status: 500 });
        }
        return Response.json({
          status: 'OK',
          code: 200,
          data: {
            entry: { stopTimes: [{ departureTime: 100, stopId }] },
            references: { stops: { [stopId]: { id: stopId } } }
          }
        });
      };

      // Batch includes: valid stop BKK_OK1, invalid/error stop BKK_ERR, and emma namespaced stop emma:1:005510017
      const path = '/api/arrivals-and-departures-for-stops.json?stopId=BKK_OK1,BKK_ERR,emma:1:005510017';
      const res = await call(path);
      assert.equal(res.status, 200);
      const json = await res.json();

      // emma stop should NOT be fetched from BKK upstream
      assert(!upstreamCalls.includes('emma:1:005510017'));
      assert(upstreamCalls.includes('BKK_OK1'));
      assert(upstreamCalls.includes('BKK_ERR'));

      // successful vs failed
      assert.deepEqual(json.data.successfulStopIds, ['BKK_OK1']);
      assert.deepEqual(json.data.failedStopIds, ['BKK_ERR', 'emma:1:005510017']);

      // Valid stopTimes are preserved and not erased by errors
      assert.equal(json.data.entry.stopTimes.length, 1);
      assert.equal(json.data.entry.stopTimes[0].stopId, 'BKK_OK1');
      assert(json.data.references.stops['BKK_OK1']);

      // PARTIAL FAILURE MUST NOT BE CACHED!
      await Promise.all(pending);
      assert.equal(stored.size, 0, 'Partial failure must not be cached');
    });

    await t.test('arrivals-and-departures-for-stops: secret redaction in batch upstream error body', async () => {
      stored.clear();
      globalThis.fetch = async (url) => {
        const stopId = new URL(url).searchParams.get('stopId');
        if (stopId === 'BKK_FAIL') {
          return Response.json({
            status: 'ERROR',
            code: 404,
            data: { entry: { error: `Stop ${stopId} not found with key ${secret}` } }
          });
        }
        return Response.json({
          status: 'OK',
          code: 200,
          data: {
            entry: { stopTimes: [] },
            references: {}
          }
        });
      };

      const path = '/api/arrivals-and-departures-for-stops.json?stopId=BKK_OK,BKK_FAIL';
      const res = await call(path);
      const body = await res.text();
      assert(!body.includes(secret), 'Secret must be completely redacted');
      const json = JSON.parse(body);
      assert.deepEqual(json.data.successfulStopIds, ['BKK_OK']);
      assert.deepEqual(json.data.failedStopIds, ['BKK_FAIL']);
    });

    // 3. Planner travel modes: TRANSIT,WALK, BUS,WALK, BUS,COACH,WALK, WALK
    await t.test('planner travel modes: valid modes accepted, invalid modes rejected', async () => {
      globalThis.fetch = async () => Response.json({
        status: 'OK',
        code: 200,
        data: { entry: { plan: { itineraries: [] } } }
      });

      const validModes = ['TRANSIT,WALK', 'BUS,WALK', 'BUS,COACH,WALK', 'WALK'];
      for (const mode of validModes) {
        const path = `/api/plan-trip.json?fromPlace=A::47.5,19.04&toPlace=B::47.6,19.1&mode=${encodeURIComponent(mode)}`;
        const res = await call(path);
        assert.equal(res.status, 200, `Mode ${mode} should be accepted`);
      }

      const invalidModes = ['BICYCLE', 'CAR', 'SUBWAY,WALK', 'TRAM,WALK', 'BUS', 'TRANSIT'];
      for (const mode of invalidModes) {
        const path = `/api/plan-trip.json?fromPlace=A::47.5,19.04&toPlace=B::47.6,19.1&mode=${encodeURIComponent(mode)}`;
        const res = await call(path);
        assert.equal(res.status, 400, `Mode ${mode} should be rejected`);
      }
    });

    // 4. Parameter validation & boundaries for new endpoints
    await t.test('parameter validation and boundaries for new endpoints', async () => {
      globalThis.fetch = async () => Response.json({ status: 'OK', code: 200, data: { entry: {} } });

      // related strict boolean
      assert.equal((await call('/api/route-details.json?routeId=BKK_5200&related=true')).status, 200);
      assert.equal((await call('/api/route-details.json?routeId=BKK_5200&related=false')).status, 200);
      assert.equal((await call('/api/route-details.json?routeId=BKK_5200&related=yes')).status, 400);
      assert.equal((await call('/api/route-details.json?routeId=BKK_5200&related=1')).status, 400);

      // Date validation
      // route-details and schedule-for-stop accept 8-digit YYYYMMDD
      assert.equal((await call('/api/route-details.json?routeId=BKK_5200&date=20261110')).status, 200);
      assert.equal((await call('/api/route-details.json?routeId=BKK_5200&date=2026-11-10')).status, 400);
      assert.equal((await call('/api/schedule-for-stop.json?stopId=BKK_F01037&date=20261110')).status, 200);
      assert.equal((await call('/api/schedule-for-stop.json?stopId=BKK_F01037&date=2026-11-10')).status, 400);

      // compact=true rejected on non-planner endpoints
      assert.equal((await call('/api/route-details.json?routeId=BKK_5200&compact=true')).status, 400);
      assert.equal((await call('/api/route-details-for-stop.json?stopId=BKK_F01037&compact=true')).status, 400);
      assert.equal((await call('/api/references.json?stopId=BKK_F01037&compact=true')).status, 400);
      assert.equal((await call('/api/schedule-for-stop.json?stopId=BKK_F01037&compact=true')).status, 400);
      assert.equal((await call('/api/arrivals-and-departures-for-stops.json?stopId=BKK_F01037&compact=true')).status, 400);

      // arrivals-and-departures-for-stops max 6 stops
      const sixStops = 'BKK_1,BKK_2,BKK_3,BKK_4,BKK_5,BKK_6';
      const sevenStops = 'BKK_1,BKK_2,BKK_3,BKK_4,BKK_5,BKK_6,BKK_7';
      assert.equal((await call(`/api/arrivals-and-departures-for-stops.json?stopId=${sixStops}`)).status, 200);
      assert.equal((await call(`/api/arrivals-and-departures-for-stops.json?stopId=${sevenStops}`)).status, 400);

      // references max 10 IDs per field
      const tenIds = Array.from({ length: 10 }, (_, i) => `BKK_${i}`).join(',');
      const elevenIds = Array.from({ length: 11 }, (_, i) => `BKK_${i}`).join(',');
      assert.equal((await call(`/api/references.json?stopId=${tenIds}`)).status, 200);
      assert.equal((await call(`/api/references.json?stopId=${elevenIds}`)).status, 400);

      // unknown parameters rejected
      assert.equal((await call('/api/route-details.json?routeId=BKK_5200&foo=bar')).status, 400);
      assert.equal((await call('/api/schedule-for-stop.json?stopId=BKK_F01037&foo=bar')).status, 400);

      // client-supplied key rejected
      assert.equal((await call('/api/route-details.json?routeId=BKK_5200&key=mykey')).status, 400);
    });

    // 5. Upstream redirect prevention (3xx -> 502)
    await t.test('upstream redirect prevents credential leakage', async () => {
      globalThis.fetch = async () => new Response(null, {
        status: 302,
        headers: { Location: 'https://attacker.example/intercept?key=' + secret }
      });
      const res = await call('/api/route-details.json?routeId=BKK_5200');
      assert.equal(res.status, 502);
      const text = await res.text();
      assert(!text.includes(secret));
      assert(!text.includes('attacker.example'));
    });
  } finally {
    globalThis.fetch = originalFetch;
    globalThis.caches = originalCaches;
  }
});
