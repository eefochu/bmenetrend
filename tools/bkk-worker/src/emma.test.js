import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mergeVehicles, mergeStops, mapVehicle, mapStop, inArea } from './emma.js';
import worker from './index.js';

const now = Math.floor(Date.now() / 1000);
const route = { gtfsId: '1:R1', shortName: '<span>&#383;</span>', longName: 'Z30', mode: 'RAIL', color: 'FECC00' };
const rawVehicle = (id, extra = {}) => ({ vehicleId: '1:' + id, lat: 47.5, lon: 19.08, lastUpdated: now,
  trip: { gtfsId: '1:T' + id, tripShortName: '12345 test train', tripHeadsign: 'Keleti', route }, ...extra });
const rawStop = (id, name = 'Budapest-Keleti', extra = {}) => ({ gtfsId: '1:' + id, name, lat: 47.500278, lon: 19.084167, vehicleMode: 'RAIL', ...extra });
const bkkStop = { id: 'BKK_005510017_0', parentStationId: 'BKK_005510017', name: 'Budapest-Keleti', lat: 47.50023, lon: 19.083872, type: 'RAIL', locationType: 0 };
const bkkVehicle = { vehicleId: 'BKK_945514150149', location: { lat: 47.51, lon: 19.09 }, routeId: 'BKK_Z30' };
const emptyRefs = () => ({ routes: {}, stops: {}, trips: {} });
const env = { BKK_API_KEY: 'private-test-key', ALLOWED_ORIGINS: '*', REQUEST_LIMITER: { limit: async () => ({ success: true }) }, PLAN_LIMITER: { limit: async () => ({ success: true }) } };
const ctx = { waitUntil: () => {} };

test('identity and scope rules', () => {
  const fallback = mapVehicle(rawVehicle('945514150149'), emptyRefs());
  const missing = mapVehicle(rawVehicle('955501173780'), emptyRefs());
  const merged = mergeVehicles([bkkVehicle], [fallback, missing, missing]);
  assert.equal(merged.length, 2); assert.equal(merged[0], bkkVehicle);
  assert.equal(merged[1].source, 'emma'); assert.equal(merged[1].vehicleId, 'BKK_955501173780');
  // Do not collapse separate trains standing at identical coordinates.
  assert.equal(mergeVehicles([], [fallback, missing]).length, 2);
  assert.equal(mergeVehicles([{ vehicleId: 'BKK_111-222', location: { lat: 47.5, lon: 19.08 } }], [{ vehicleId: 'BKK_222', location: { lat: 47.5, lon: 19.08 } }]).length, 1);
  assert.equal(mergeVehicles([{ vehicleId: fallback.vehicleId }], [fallback])[0], fallback);
  assert.equal(mapVehicle(rawVehicle('stale', { lastUpdated: now - 301 }), emptyRefs()), null);
  assert.equal(mapVehicle(rawVehicle('bad', { lat: null }), emptyRefs()), null);
  assert.equal(mapVehicle(rawVehicle('bus', { trip: { route: { ...route, mode: 'BUS' } } }), emptyRefs()), null);
  assert.equal(mapVehicle(rawVehicle('hev', { trip: { route: { ...route, mode: 'SUBURBAN_RAILWAY' } } }), emptyRefs()), null);
  assert.equal(mapStop(rawStop('bus', 'Keleti', { vehicleMode: 'BUS' })), null);
  const duplicate = mapStop(rawStop('005510017_0', 'Different feed name', { parentStation: { gtfsId: '1:005510017' } }));
  const alias = mapStop(rawStop('different-id', 'Budapest Keleti'));
  const distant = mapStop(rawStop('distant', 'Budapest Keleti', { lat: 47.6 }));
  const bus = { ...bkkStop, id: 'BKK_bus', parentStationId: null, type: 'BUS' };
  const stations = mergeStops([bkkStop, bus], [duplicate, alias, distant]);
  assert.equal(stations.length, 3); assert.equal(stations[0], bkkStop); assert(stations.includes(bus)); assert(stations.includes(distant));
  assert(inArea(missing, new URLSearchParams('lat=47.5&lon=19.08&radius=100')));
  assert(!inArea(missing, new URLSearchParams('lat=48&lon=20&latSpan=0.01&lonSpan=0.01')));
});

test('fallback endpoint integration', async t => {
  const originalFetch = globalThis.fetch, originalCaches = globalThis.caches;
  const calls = []; let failEmma = false;
  globalThis.caches = undefined;
  globalThis.fetch = async (url, options) => {
    calls.push(String(url));
    if (String(url).includes('mavplusz.hu')) {
      if (failEmma) return new Response('unavailable', { status: 503 });
      const { query, variables } = JSON.parse(options.body);
      if (query.includes('vehiclePositions')) return Response.json({ data: { vehiclePositions: [rawVehicle('945514150149'), rawVehicle('955501173780'), rawVehicle('far', { lat: 48.5 }), rawVehicle('old', { lastUpdated: now - 600 })] } });
      if (query.includes('stoptimesWithoutPatterns')) {
        const serviceDay = Math.floor(now / 86400) * 86400;
        const departure = { scheduledDeparture: now - serviceDay + 120, realtimeDeparture: now - serviceDay + 180, realtime: true, serviceDay, headsign: 'Győr', trip: { gtfsId: '1:123', route } };
        return Response.json({ data: { stop: { ...rawStop(variables.id.slice(2)), stoptimesWithoutPatterns: [departure, departure] } } });
      }
      if (query.includes('trip(id:')) return Response.json({ data: { trip: { gtfsId: variables.id, route,
        geometry: [[19.08, 47.5], [19.09, 47.51]], stops: [rawStop('station')], stoptimes: [{ serviceDay: 0, scheduledDeparture: now, realtimeDeparture: now + 60, realtime: true }] } } });
      const stops = [rawStop('005510017_0'), rawStop('new-station', 'Missing rail station')];
      return Response.json({ data: query.includes('stopsByBbox') ? { stopsByBbox: stops } : { stops } });
    }
    const path = new URL(url).pathname;
    const data = { references: emptyRefs() };
    if (path.endsWith('/search.json')) {
      data.entry = { stopIds: [bkkStop.id], routeIds: [] }; data.references.stops[bkkStop.id] = bkkStop;
    } else if (path.endsWith('/stops-for-location.json')) data.list = [bkkStop];
    else if (path.endsWith('/plan-trip.json')) data.entry = { plan: { itineraries: [{ legs: [] }] } };
    else data.list = [bkkVehicle];
    return Response.json({ status: 'OK', currentTime: Date.now(), data });
  };
  const call = async path => {
    const response = await worker.fetch(new Request('https://proxy.test' + path), env, ctx);
    return { response, json: await response.json() };
  };
  try {
    await t.test('map adds missing rail vehicles and preserves BKK duplicate', async () => {
      const { json } = await call('/api/vehicles-for-location.json?lat=47.5&lon=19.08&latSpan=0.02&lonSpan=0.02&includeReferences=false');
      assert.equal(json.data.list.length, 2); assert.deepEqual(json.data.list[0], bkkVehicle);
      assert.equal(json.data.list[1].source, 'emma'); assert.equal(json.fallback.added, 1);
      assert(json.data.references.routes[json.data.list[1].routeId]);
    });
    await t.test('station map and search deduplicate shared railway IDs', async () => {
      const { json } = await call('/api/stops-for-location.json?lat=47.5&lon=19.08&latSpan=0.02&lonSpan=0.02');
      assert.equal(json.data.list.length, 2); assert.deepEqual(json.data.list[0], bkkStop);
      const result = (await call('/api/search.json?query=Keleti')).json;
      assert.equal(result.data.entry.stopIds.length, 2);
      assert(result.data.entry.vehicleIds.includes(bkkVehicle.vehicleId));
      assert.deepEqual(result.data.references.vehicles[bkkVehicle.vehicleId], bkkVehicle);
    });
    await t.test('fallback exact vehicle IDs work outside Budapest', async () => {
      const { json } = await call('/api/vehicles.json?vehicleId=BKK_955501173780');
      assert(json.data.list.some(v => v.vehicleId === 'BKK_955501173780'));
    });
    await t.test('namespaced stop boards and trip geometry use EMMA directly', async () => {
      let count = calls.filter(c => c.includes('futar.bkk.hu')).length;
      const board = await call('/api/arrivals-and-departures-for-stop.json?stopId=emma:1:station&limit=12');
      assert.equal(board.response.status, 200); assert.equal(board.json.data.entry.stopTimes.length, 1);
      const departure = board.json.data.entry.stopTimes[0]; assert.equal(departure.predictedDepartureTime - departure.departureTime, 60);
      const trip = await call('/api/trip-details.json?tripId=emma:1:123');
      assert.deepEqual(trip.json.data.entry.geometry[0], [47.5, 19.08]);
      assert(trip.json.data.references.stops['emma:1:station']);
      assert.equal(calls.filter(c => c.includes('futar.bkk.hu')).length, count);
      assert.equal((await call('/api/trip-details.json?tripId=emma:hkir:123')).response.status, 502);
    });
    await t.test('successful BKK journey planning never invokes EMMA', async () => {
      const before = calls.filter(c => c.includes('mavplusz.hu')).length;
      await call('/api/plan-trip.json?fromPlace=A::47.5,19.08&toPlace=B::47.6,19.1&mode=TRANSIT,WALK');
      assert.equal(calls.filter(c => c.includes('mavplusz.hu')).length, before);
    });
    await t.test('EMMA outage preserves primary data and exposes degraded status', async () => {
      failEmma = true;
      const { json } = await call('/api/vehicles-for-location.json?lat=47.5&lon=19.08&radius=1000');
      assert.deepEqual(json.data.list, [bkkVehicle]); assert.equal(json.fallback.status, 'unavailable');
    });
  } finally { globalThis.fetch = originalFetch; globalThis.caches = originalCaches; }
});
