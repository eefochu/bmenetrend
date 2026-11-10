import { test } from 'node:test';
import assert from 'node:assert/strict';
import worker from './index.js';

const secret = 'test-private-bkk-key';
const makeEnv = () => ({ BKK_API_KEY: secret, ALLOWED_ORIGINS: '*', REQUEST_LIMITER: { limit: async () => ({ success: true }) }, PLAN_LIMITER: { limit: async () => ({ success: true }) } });
const query = '/api/vehicles-for-location.json?lat=47.5&lon=19.04&latSpan=0.01&lonSpan=0.01';

test('proxy integration and abuse boundaries', async t => {
  const originalFetch = globalThis.fetch, originalCaches = globalThis.caches;
  const stored = new Map(), pending = [], upstreamCalls = [];
  const ctx = { waitUntil: p => pending.push(p) };
  let payload = { status: 'OK', data: { list: [], entry: { requestParameters: { key: secret }, note: 'echo ' + secret } } };
  let upstreamStatus = 200;
  globalThis.fetch = async (url, options) => {
    if (String(url).includes('mavplusz.hu')) return Response.json({ data: { vehiclePositions: [], stops: [], stopsByBbox: [] } });
    upstreamCalls.push({ url: new URL(url), options });
    return Response.json(payload, { status: upstreamStatus });
  };
  globalThis.caches = { default: {
    match: async key => stored.get(key.url)?.clone(),
    put: async (key, value) => { stored.set(key.url, value.clone()); }
  } };
  const call = (path, env = makeEnv(), init = {}) => worker.fetch(new Request('https://proxy.example' + path, init), env, ctx);
  try {
    await t.test('forwards only to BKK with secret, redacts echoes, caches sanitized data', async () => {
      const response = await call(query, makeEnv(), { headers: { Origin: 'null' } });
      assert.equal(response.status, 200); assert.equal(response.headers.get('Access-Control-Allow-Origin'), '*');
      assert.equal(response.headers.get('X-Proxy-Cache'), 'MISS');
      const text = await response.text(); assert(!text.includes(secret)); assert(!text.includes('"key"'));
      assert.equal(upstreamCalls[0].url.origin, 'https://futar.bkk.hu');
      assert.equal(upstreamCalls[0].url.searchParams.get('key'), secret);
      assert.equal(upstreamCalls[0].options.redirect, 'manual');
      await Promise.all(pending);
      assert([...stored.keys()].every(k => !k.includes(secret) && !k.includes('key=')));
      assert.equal((await call(query)).headers.get('X-Proxy-Cache'), 'HIT'); assert.equal(upstreamCalls.length, 1);
    });
    await t.test('rejects arbitrary destinations, methods, injected keys, duplicates, and oversized queries', async () => {
      const before = upstreamCalls.length;
      for (const path of ['/api/not-real.json', query + '&key=evil', query + '&url=https://evil.example', query + '&lat=48', query.replace('latSpan=0.01', 'latSpan=1'), '/api/search.json', '/api/vehicles-for-location.json?lat=47&lon=19']) {
        assert([400, 404].includes((await call(path)).status), path);
      }
      assert.equal((await call(query, makeEnv(), { method: 'POST' })).status, 405);
      assert.equal(upstreamCalls.length, before);
    });
    await t.test('rate limits apply even to cached requests and planner has its own limit', async () => {
      const env = makeEnv(); env.REQUEST_LIMITER.limit = async () => ({ success: false });
      const r = await call(query, env); assert.equal(r.status, 429); assert.equal(r.headers.get('Retry-After'), '60');
      const planEnv = makeEnv(); planEnv.PLAN_LIMITER.limit = async () => ({ success: false });
      assert.equal((await call('/api/plan-trip.json?fromPlace=A::47.5,19.04&toPlace=B::47.6,19.1&mode=WALK', planEnv)).status, 429);
    });
    await t.test('planner responses remain compatible and gain 5s cache on exact repeat', async () => {
      const path = '/api/plan-trip.json?fromPlace=A::47.5,19.04&toPlace=B::47.6,19.1&mode=TRANSIT,WALK&arriveBy=true&date=2026-11-11&time=14:30';
      const before = upstreamCalls.length, count = stored.size;
      const first = await call(path);
      assert.equal(first.status, 200);
      assert.equal(first.headers.get('X-Proxy-Cache'), 'MISS');
      assert.equal((await first.json()).data.entry.requestParameters.key, undefined);
      await Promise.all(pending);
      assert.equal(stored.size, count + 1);
      const second = await call(path);
      assert.equal(second.status, 200);
      assert.equal(second.headers.get('X-Proxy-Cache'), 'HIT');
      assert.equal((await second.json()).data.entry.requestParameters.key, undefined);
      assert.equal(upstreamCalls.length, before + 1);
      assert.equal(upstreamCalls.at(-1).url.searchParams.get('arriveBy'), 'true');
    });
    await t.test('successful BKK responses remain cacheable for 5s during EMMA outage', async () => {
      stored.clear();
      let emmaFailed = true;
      const prevFetch = globalThis.fetch;
      globalThis.fetch = async (url, options) => {
        if (String(url).includes('mavplusz.hu')) {
          if (emmaFailed) return new Response('EMMA 503', { status: 503 });
          return Response.json({ data: { vehiclePositions: [], stops: [], stopsByBbox: [] } });
        }
        upstreamCalls.push({ url: new URL(url), options });
        return Response.json(payload, { status: upstreamStatus });
      };
      try {
        const first = await call(query);
        assert.equal(first.status, 200);
        assert.equal(first.headers.get('X-Proxy-Cache'), 'MISS');
        const firstJson = await first.json();
        assert.equal(firstJson.fallback?.status, 'unavailable');
        await Promise.all(pending);
        // Verify cached entry has short 5s TTL
        const cached = [...stored.values()].at(-1);
        assert(cached, 'Cache should have stored degraded response');
        assert.equal(cached.headers.get('Cache-Control'), 'public, max-age=5');
        const second = await call(query);
        assert.equal(second.status, 200);
        assert.equal(second.headers.get('X-Proxy-Cache'), 'HIT');
        const secondJson = await second.json();
        assert.equal(secondJson.fallback?.status, 'unavailable');
      } finally {
        globalThis.fetch = prevFetch;
      }
    });
    await t.test('configuration and CORS fail closed while native clients work', async () => {
      assert.equal((await call(query, { ...makeEnv(), REQUEST_LIMITER: null })).status, 503);
      assert.equal((await call(query, { ...makeEnv(), BKK_API_KEY: '' })).status, 503);
      const env = { ...makeEnv(), ALLOWED_ORIGINS: 'https://app.example' };
      assert.equal((await call(query, env, { headers: { Origin: 'https://evil.example' } })).status, 403);
      assert.equal((await call(query, env)).status, 200);
      const preflight = await call(query, env, { method: 'OPTIONS', headers: { Origin: 'https://app.example' } });
      assert.equal(preflight.status, 204); assert.equal(preflight.headers.get('Access-Control-Allow-Origin'), 'https://app.example');
    });
    await t.test('upstream errors are not cached or leaked, including exception URLs', async () => {
      stored.clear(); const count = stored.size;
      upstreamStatus = 302;
      const redirect = await call(query); assert.equal(redirect.status, 502);
      upstreamStatus = 429; let r = await call(query); assert.equal(r.status, 429); assert(!((await r.text()).includes(secret)));
      upstreamStatus = 200; payload = { status: 'LIMIT_EXCEEDED' };
      await call(query); await Promise.all(pending); assert.equal(stored.size, count);
      globalThis.fetch = async () => { throw new Error('Failed URL https://example?key=' + secret); };
      r = await call(query); assert.equal(r.status, 502); assert(!((await r.text()).includes(secret)));
    });
  } finally { globalThis.fetch = originalFetch; globalThis.caches = originalCaches; }
});
