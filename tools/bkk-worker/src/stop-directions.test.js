import { test } from 'node:test';
import assert from 'node:assert/strict';
import worker from './index.js';

const secret = 'test-private-bkk-key-directions';
const makeEnv = () => ({
  BKK_API_KEY: secret,
  ALLOWED_ORIGINS: '*',
  REQUEST_LIMITER: { limit: async () => ({ success: true }) },
  PLAN_LIMITER: { limit: async () => ({ success: true }) }
});

const query = '/api/stop-directions-for-location.json?lat=47.497&lon=19.054&latSpan=0.01&lonSpan=0.01';

test('stop-directions-for-location integration', async t => {
  const originalFetch = globalThis.fetch;
  const originalCaches = globalThis.caches;
  const stored = new Map();
  const pending = [];
  const upstreamCalls = [];
  const emmaCalls = [];
  const ctx = { waitUntil: p => pending.push(p) };

  const payload = {
    status: 'OK',
    data: {
      list: [
        { id: 'BKK_F1', locationType: 0, direction: '-178', lat: 47.497, lon: 19.054, name: 'Platform 1' },
        { id: 'volan_1_2', locationType: 0, direction: 0, lat: 47.497, lon: 19.054, name: 'Volan Platform' },
        { id: 'BKK_F2', locationType: 0, direction: '100', lat: 47.497, lon: 19.054, name: 'Platform 2' },
        { id: 'BKK_F3', locationType: 0, direction: '360', lat: 47.497, lon: 19.054, name: 'Platform 3' },
        { id: 'BKK_F4', locationType: 0, direction: '-360', lat: 47.497, lon: 19.054, name: 'Platform 4' },
        // Unknowns / blanks: 0 vs blank
        { id: 'BKK_BLANK', locationType: 0, direction: '', lat: 47.497, lon: 19.054, name: 'Blank' },
        { id: 'BKK_NULL', locationType: 0, direction: null, lat: 47.497, lon: 19.054, name: 'Null' },
        // Parent station (locationType 1) - must be omitted
        { id: 'BKK_PARENT', locationType: 1, direction: '90', lat: 47.497, lon: 19.054, name: 'Parent Station' },
        // Invalid directions - non-numeric, booleans, out-of-range [-360, 360]
        { id: 'BKK_INV_TXT', locationType: 0, direction: 'north', lat: 47.497, lon: 19.054, name: 'Invalid Text' },
        { id: 'BKK_INV_TRUE', locationType: 0, direction: true, lat: 47.497, lon: 19.054, name: 'Invalid Bool' },
        { id: 'BKK_INV_FALSE', locationType: 0, direction: false, lat: 47.497, lon: 19.054, name: 'Invalid Bool' },
        { id: 'BKK_INV_OVER', locationType: 0, direction: '361', lat: 47.497, lon: 19.054, name: 'Over Range' },
        { id: 'BKK_INV_UNDER', locationType: 0, direction: '-361', lat: 47.497, lon: 19.054, name: 'Under Range' }
      ],
      entry: {
        requestParameters: { key: secret },
        note: 'echo ' + secret
      }
    }
  };

  let upstreamStatus = 200;

  globalThis.fetch = async (url, options) => {
    const urlStr = String(url);
    if (urlStr.includes('mavplusz.hu')) {
      emmaCalls.push({ url: new URL(urlStr), options });
      return Response.json({ data: { vehiclePositions: [], stops: [], stopsByBbox: [] } });
    }
    upstreamCalls.push({ url: new URL(urlStr), options });
    return Response.json(payload, { status: upstreamStatus });
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
    await t.test('forwards exactly one BKK stops-for-location upstream with includeReferences=false and bypasses EMMA', async () => {
      const response = await call(query);
      assert.equal(response.status, 200);
      assert.equal(upstreamCalls.length, 1);
      const upstream = upstreamCalls[0];
      assert.equal(upstream.url.origin, 'https://futar.bkk.hu');
      assert.equal(upstream.url.pathname, '/api/query/v1/ws/otp/api/where/stops-for-location.json');
      assert.equal(upstream.url.searchParams.get('includeReferences'), 'false');
      assert.equal(upstream.url.searchParams.get('key'), secret);
      assert.equal(upstream.url.searchParams.get('version'), '4');
      assert.equal(upstream.url.searchParams.get('lat'), '47.497');
      assert.equal(upstream.url.searchParams.get('lon'), '19.054');
      assert.equal(emmaCalls.length, 0, 'Must bypass EMMA entirely without fanout');
    });

    await t.test('returns exact compact output, normalizes [-360, 360] to [0, 360), handles 0 vs blank, and sanitizes secrets', async () => {
      const response = await call(query);
      assert.equal(response.status, 200);
      const text = await response.text();
      assert(!text.includes(secret), 'Secret must be sanitized');
      assert(!text.includes('requestParameters'), 'Echoed parameters must not leak');

      const json = JSON.parse(text);
      assert.equal(json.status, 'OK');
      assert(Array.isArray(json.data?.list), 'Must return data.list array');

      const list = json.data.list;
      // Should only contain valid platforms with normalized finite numeric bearings [0, 360)
      assert.deepEqual(list, [
        { id: 'BKK_F1', bearing: 182 },
        { id: 'volan_1_2', bearing: 0 },
        { id: 'BKK_F2', bearing: 100 },
        { id: 'BKK_F3', bearing: 0 },
        { id: 'BKK_F4', bearing: 0 }
      ]);

      // Ensure no original fields or invented angles leak into items
      for (const item of list) {
        assert.deepEqual(Object.keys(item).sort(), ['bearing', 'id']);
        assert(item.bearing >= 0 && item.bearing < 360, `Bearing ${item.bearing} must be in [0, 360)`);
      }
    });

    await t.test('caches successful response for 3600s and respects request limiter', async () => {
      stored.clear();
      upstreamCalls.length = 0;
      pending.length = 0;

      const first = await call(query);
      assert.equal(first.status, 200);
      assert.equal(first.headers.get('X-Proxy-Cache'), 'MISS');
      await Promise.all(pending);

      assert.equal(stored.size, 1);
      const cached = [...stored.values()][0];
      assert.equal(cached.headers.get('Cache-Control'), 'public, max-age=3600');
      assert([...stored.keys()].every(k => !k.includes(secret) && !k.includes('key=')));

      const second = await call(query);
      assert.equal(second.status, 200);
      assert.equal(second.headers.get('X-Proxy-Cache'), 'HIT');
      assert.equal(upstreamCalls.length, 1, 'Cache hit must not call upstream');

      // Normal request limiter still applies
      const limitedEnv = makeEnv();
      limitedEnv.REQUEST_LIMITER.limit = async () => ({ success: false });
      const limited = await call(query, limitedEnv);
      assert.equal(limited.status, 429);
    });

    await t.test('upstream errors are not cached', async () => {
      stored.clear();
      upstreamCalls.length = 0;
      pending.length = 0;

      upstreamStatus = 502;
      const errorResp = await call(query);
      assert([502, 504].includes(errorResp.status));
      await Promise.all(pending);
      assert.equal(stored.size, 0, 'Errors must not be cached');
    });

    await t.test('upstream HTTP 200 with status: ERROR, limitExceeded, and malformed body return 502 and are not cached', async () => {
      stored.clear();
      upstreamCalls.length = 0;
      pending.length = 0;

      // 1. Upstream returns HTTP 200 but status: 'ERROR'
      let customResponse = Response.json({ status: 'ERROR', code: 400, text: 'Malformed query' }, { status: 200 });
      globalThis.fetch = async () => customResponse;

      let resp = await call(query);
      assert.equal(resp.status, 502);
      await Promise.all(pending);
      assert.equal(stored.size, 0, 'HTTP 200 ERROR must not be cached');

      // 2. Upstream returns HTTP 200 OK but limitExceeded: true
      customResponse = Response.json({ status: 'OK', data: { limitExceeded: true, list: [] } }, { status: 200 });
      resp = await call(query);
      assert.equal(resp.status, 502);
      await Promise.all(pending);
      assert.equal(stored.size, 0, 'HTTP 200 with limitExceeded must not be cached');

      // 3. Upstream returns HTTP 200 with malformed JSON body
      customResponse = new Response('<html><head><title>Bad Gateway</title></head></html>', {
        status: 200,
        headers: { 'Content-Type': 'text/html' }
      });
      resp = await call(query);
      assert.equal(resp.status, 502);
      await Promise.all(pending);
      assert.equal(stored.size, 0, 'Malformed response must not be cached');
    });

    await t.test('boolean, blank, hex, Infinity, NaN directions are omitted and unknown headings are not invented', async () => {
      const customPayload = {
        status: 'OK',
        data: {
          list: [
            { id: 'BKK_BOOL_TRUE', locationType: 0, direction: true, lat: 47.497, lon: 19.054 },
            { id: 'BKK_BOOL_FALSE', locationType: 0, direction: false, lat: 47.497, lon: 19.054 },
            { id: 'BKK_BLANK_EMPTY', locationType: 0, direction: '', lat: 47.497, lon: 19.054 },
            { id: 'BKK_BLANK_SPACES', locationType: 0, direction: '    ', lat: 47.497, lon: 19.054 },
            { id: 'BKK_HEX_VALUE', locationType: 0, direction: '0x10', lat: 47.497, lon: 19.054 },
            { id: 'BKK_HEX_PREFIX', locationType: 0, direction: '0xFF', lat: 47.497, lon: 19.054 },
            { id: 'BKK_NUM_INF', locationType: 0, direction: Infinity, lat: 47.497, lon: 19.054 },
            { id: 'BKK_NUM_NEG_INF', locationType: 0, direction: -Infinity, lat: 47.497, lon: 19.054 },
            { id: 'BKK_NUM_NAN', locationType: 0, direction: NaN, lat: 47.497, lon: 19.054 },
            { id: 'BKK_STR_INF', locationType: 0, direction: 'Infinity', lat: 47.497, lon: 19.054 },
            { id: 'BKK_STR_NAN', locationType: 0, direction: 'NaN', lat: 47.497, lon: 19.054 },
            { id: 'BKK_VALID_ZERO', locationType: 0, direction: 0, lat: 47.497, lon: 19.054 },
            { id: 'BKK_VALID_POS', locationType: 0, direction: '+125', lat: 47.497, lon: 19.054 },
            { id: 'BKK_VALID_NEG', locationType: 0, direction: '-45', lat: 47.497, lon: 19.054 }
          ]
        }
      };

      globalThis.fetch = async () => Response.json(customPayload, { status: 200 });

      const resp = await call(query);
      assert.equal(resp.status, 200);
      const body = await resp.json();
      assert.equal(body.status, 'OK');
      const list = body.data.list;

      assert.deepEqual(list, [
        { id: 'BKK_VALID_ZERO', bearing: 0 },
        { id: 'BKK_VALID_POS', bearing: 125 },
        { id: 'BKK_VALID_NEG', bearing: 315 }
      ]);

      const returnedIds = new Set(list.map(item => item.id));
      assert(!returnedIds.has('BKK_BOOL_TRUE'));
      assert(!returnedIds.has('BKK_BOOL_FALSE'));
      assert(!returnedIds.has('BKK_BLANK_EMPTY'));
      assert(!returnedIds.has('BKK_BLANK_SPACES'));
      assert(!returnedIds.has('BKK_HEX_VALUE'));
      assert(!returnedIds.has('BKK_HEX_PREFIX'));
      assert(!returnedIds.has('BKK_NUM_INF'));
      assert(!returnedIds.has('BKK_NUM_NEG_INF'));
      assert(!returnedIds.has('BKK_NUM_NAN'));
      assert(!returnedIds.has('BKK_STR_INF'));
      assert(!returnedIds.has('BKK_STR_NAN'));
    });

    await t.test('validates coordinate limits and required spans/radius', async () => {
      for (const path of [
        '/api/stop-directions-for-location.json', // missing lat/lon
        '/api/stop-directions-for-location.json?lat=47.5&lon=19.0', // missing radius or spans
        '/api/stop-directions-for-location.json?lat=40.0&lon=19.0&radius=500', // lat out of range [45, 49]
        '/api/stop-directions-for-location.json?lat=47.5&lon=19.0&latSpan=1.0&lonSpan=0.01' // span too large
      ]) {
        const resp = await call(path);
        assert.equal(resp.status, 400, `Expected 400 for path: ${path}`);
      }
    });
  } finally {
    globalThis.fetch = originalFetch;
    globalThis.caches = originalCaches;
  }
});
