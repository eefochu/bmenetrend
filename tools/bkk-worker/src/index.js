import { supplement, emmaDetails, mergeVehicles } from './emma.js';
const UPSTREAM = 'https://futar.bkk.hu/api/query/v1/ws/otp/api/where/';
const ENDPOINTS = {
  'vehicles-for-location': { fields: 'lat lon latSpan lonSpan radius', required: 'lat lon', ttl: 5 },
  'stops-for-location': { fields: 'lat lon latSpan lonSpan radius', required: 'lat lon', ttl: 300 },
  'search': { fields: 'query lat lon minResult', required: 'query', ttl: 10 },
  'vehicles': { fields: 'vehicleId', required: 'vehicleId', ttl: 5 },
  'vehicles-for-route': { fields: 'routeId', required: 'routeId', ttl: 5 },
  'arrivals-and-departures-for-stop': { fields: 'stopId minutesBefore minutesAfter limit', required: 'stopId', ttl: 10 },
  'arrivals-and-departures-for-stops': { fields: 'stopId minutesBefore minutesAfter limit', required: 'stopId', ttl: 10 },
  'route-details': { fields: 'routeId date related', required: 'routeId', ttl: 300 },
  'route-details-for-stop': { fields: 'stopId', required: 'stopId', ttl: 300 },
  'references': { fields: 'stopId routeId tripId agencyId alertId', required: '', ttl: 300 },
  'schedule-for-stop': { fields: 'stopId date', required: 'stopId', ttl: 300 },
  'trip-details': { fields: 'tripId date', required: 'tripId', ttl: 30 },
  'plan-trip': { fields: 'fromPlace toPlace mode date time arriveBy numItineraries showIntermediateStops wheelchair compact', required: 'fromPlace toPlace mode', ttl: 5 }
};
const RANGES = {
  lat: [45, 49], lon: [16, 23.5], latSpan: [0.00001, 0.09], lonSpan: [0.00001, 0.09],
  radius: [1, 5000], minResult: [1, 20], minutesBefore: [0, 60], minutesAfter: [1, 120], limit: [1, 50], numItineraries: [1, 3]
};
function validateParams(url, endpoint) {
  const spec = ENDPOINTS[endpoint], allowed = new Set((spec.fields + ' version includeReferences').split(' '));
  const params = new URLSearchParams();
  for (const [name, value] of url.searchParams) {
    if (!allowed.has(name) || params.has(name)) throw new Error('Unknown or repeated parameter: ' + name);
    if (!value || value.length > 300 || /[\x00-\x1f]/.test(value)) throw new Error('Invalid parameter: ' + name);
    if (name in RANGES) {
      const n = Number(value), [min, max] = RANGES[name];
      if (!Number.isFinite(n) || n < min || n > max || (!['lat', 'lon', 'latSpan', 'lonSpan', 'radius'].includes(name) && !Number.isInteger(n))) throw new Error('Out of range: ' + name);
    }
    if (name === 'version' && value !== '4') throw new Error('Only API version 4 is supported');
    if (name === 'includeReferences' && !/^(true|false|COMPACT|(?:routes|trips|stops|agencies|alerts|stations)(?:,(?:routes|trips|stops|agencies|alerts|stations))*)$/.test(value)) throw new Error('Invalid references');
    if (['vehicleId', 'routeId', 'stopId', 'tripId', 'agencyId', 'alertId'].includes(name) && (!/^[A-Za-z0-9_:.-]+(?:,[A-Za-z0-9_:.-]+)*$/.test(value) || value.split(',').length > 10)) throw new Error('Invalid identifier');
    if (['arriveBy', 'showIntermediateStops', 'wheelchair', 'compact', 'related'].includes(name) && !['true', 'false'].includes(value)) throw new Error('Invalid boolean');
    if (name === 'mode' && !['TRANSIT,WALK', 'BUS,WALK', 'BUS,COACH,WALK', 'WALK'].includes(value)) throw new Error('Unsupported travel mode');
    if (name === 'date' && !(['trip-details', 'route-details', 'schedule-for-stop'].includes(endpoint) ? /^\d{8}$/ : /^\d{4}-\d{2}-\d{2}$/).test(value)) throw new Error('Invalid date format');
    if (name === 'time' && !/^([01]\d|2[0-3]):[0-5]\d$/.test(value)) throw new Error('Invalid time');
    if (name === 'fromPlace' || name === 'toPlace') {
      const match = value.match(/^(.{1,180})::(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)$/);
      if (!match || +match[2] < 45 || +match[2] > 49 || +match[3] < 16 || +match[3] > 23.5) throw new Error('Places must use name::lat,lon within Hungary');
    }
    params.set(name, value);
  }
  for (const field of spec.required.split(' ').filter(Boolean)) if (!params.has(field)) throw new Error('Missing parameter: ' + field);
  if (endpoint === 'references' && !['stopId', 'routeId', 'tripId', 'agencyId', 'alertId'].some(k => params.has(k))) throw new Error('Specify at least one reference ID');
  if (endpoint === 'arrivals-and-departures-for-stops' && params.get('stopId').split(',').length > 6) throw new Error('At most six stops per board request');
  if (endpoint.endsWith('-for-location') && !params.has('radius') && (!params.has('latSpan') || !params.has('lonSpan'))) throw new Error('Specify radius or both spans (maximum 0.09 degrees each)');
  params.set('version', '4'); params.sort();
  return params;
}

// plan-trip echoes requestParameters.key. Also redact accidental echoes in text.
function sanitize(value, secret) {
  if (typeof value === 'string') return value.replaceAll(secret, '[redacted]');
  if (Array.isArray(value)) return value.map(v => sanitize(v, secret));
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value)
    .filter(([k]) => !['key', 'apikey', 'api_key'].includes(k.toLowerCase()))
    .map(([k, v]) => [k, sanitize(v, secret)]));
  return value;
}
const errorResponse = (status, text, extra = {}) => Response.json({ status: 'ERROR', code: status, text }, { status, headers: { 'Cache-Control': 'no-store', ...extra } });

// The Android planner consumes inline leg/stop data, not step-by-step geometry,
// elevation profiles or every alternative trip in a metro pattern. Keep the
// default response intact for the test page's route drawing.
const pick = (object, fields) => Object.fromEntries(fields.filter(key => Object.hasOwn(object || {}, key)).map(key => [key, object[key]]));
const POINT_FIELDS = 'name lat lon stopId stopCode platformCode arrival departure stopIndex stopSequence'.split(' ');
const LEG_FIELDS = 'mode startTime endTime departureDelay arrivalDelay distance agencyId agencyName routeId routeShortName routeLongName routeColor routeTextColor headsign realTime interlineWithPreviousLeg tripId serviceDate transitLeg wheelchairAccessible'.split(' ');
const ITINERARY_FIELDS = 'duration startTime endTime walkTime transitTime waitingTime walkDistance transfers generalizedCost'.split(' ');
export function compactPlan(response) {
  const plan = response.data?.entry?.plan;
  if (!plan || !Array.isArray(plan.itineraries)) return response;
  return { ...response, data: { ...response.data, references: {}, entry: { ...response.data.entry,
    plan: { ...plan, itineraries: plan.itineraries.map(itinerary => ({ ...pick(itinerary, ITINERARY_FIELDS),
      legs: (itinerary.legs || []).map(leg => ({ ...pick(leg, LEG_FIELDS), from: pick(leg.from, POINT_FIELDS), to: pick(leg.to, POINT_FIELDS),
        intermediateStops: (leg.intermediateStops || []).map(stop => pick(stop, POINT_FIELDS)) })) })) }
  } } };
}

async function batchBoards(params, secret, signal) {
  const ids = [...new Set(params.get('stopId').split(','))], results = new Array(ids.length);
  let next = 0;
  // Bound upstream fan-out. Each incoming batch still passes the normal limiter.
  await Promise.all(Array.from({ length: Math.min(3, ids.length) }, async () => {
    while (next < ids.length) {
      const i = next++, stopId = ids[i];
      if (stopId.startsWith('emma:')) { results[i] = null; continue; }
      const query = new URLSearchParams(params); query.set('stopId', stopId); query.set('key', secret);
      try {
        const response = await fetch(UPSTREAM + 'arrivals-and-departures-for-stop.json?' + query, {
          headers: { Accept: 'application/json' }, signal, redirect: 'manual'
        });
        if (!response.ok) { results[i] = null; continue; }
        const body = await response.text();
        if (body.length > 1_000_000) { results[i] = null; continue; }
        const json = sanitize(JSON.parse(body), secret);
        results[i] = json.status === 'OK' && Array.isArray(json.data?.entry?.stopTimes) && !json.data.entry.error ? json : null;
      } catch { results[i] = null; }
    }
  }));
  const references = {}, stopTimes = [], failedStopIds = [], successfulStopIds = [];
  for (let i = 0; i < ids.length; i++) {
    const result = results[i];
    if (!result) { failedStopIds.push(ids[i]); continue; }
    successfulStopIds.push(ids[i]);
    for (const [kind, values] of Object.entries(result.data.references || {})) Object.assign(references[kind] ||= {}, values);
    stopTimes.push(...result.data.entry.stopTimes.map(row => ({ ...row, stopId: row.stopId || ids[i] })));
  }
  return { status: 'OK', code: 200, text: 'OK', version: 4, currentTime: Date.now(),
    data: { entry: { stopTimes }, references, successfulStopIds, failedStopIds, limitExceeded: results.some(r => r?.data?.limitExceeded) } };
}

export default {
  async fetch(request, env, ctx) {
    const origin = request.headers.get('Origin');
    const origins = (env.ALLOWED_ORIGINS || '').split(',').map(s => s.trim()).filter(Boolean);
    const corsOrigin = origins.includes('*') ? '*' : (origin && origins.includes(origin) ? origin : null);
    const respond = (response, cacheState) => {
      const headers = new Headers(response.headers);
      headers.set('Cache-Control', 'no-store'); headers.set('X-Content-Type-Options', 'nosniff');
      headers.set('Vary', 'Origin');
      if (corsOrigin) headers.set('Access-Control-Allow-Origin', corsOrigin);
      headers.set('Access-Control-Allow-Methods', 'GET, OPTIONS');
      headers.set('Access-Control-Allow-Headers', 'Content-Type');
      headers.set('Access-Control-Expose-Headers', 'X-Proxy-Cache, Retry-After');
      if (cacheState) headers.set('X-Proxy-Cache', cacheState);
      return new Response(response.body, { status: response.status, headers });
    };
    if (origin && !corsOrigin) return respond(errorResponse(403, 'Origin is not allowed'));
    if (request.method === 'OPTIONS') return respond(new Response(null, { status: 204 }));
    if (request.method !== 'GET') return respond(errorResponse(405, 'Use GET', { Allow: 'GET, OPTIONS' }));
    const url = new URL(request.url);
    if (url.pathname === '/health') return respond(Response.json({ status: 'OK' }));
    const endpoint = url.pathname.match(/^\/api\/([a-z-]+)\.json$/)?.[1];
    if (!Object.hasOwn(ENDPOINTS, endpoint || '')) return respond(errorResponse(404, 'Unknown endpoint'));
    let params;
    try { params = validateParams(url, endpoint); }
    catch (e) { return respond(errorResponse(400, e.message)); }
    if (!env.BKK_API_KEY || !env.REQUEST_LIMITER || !env.PLAN_LIMITER) return respond(errorResponse(503, 'Worker configuration is incomplete'));
    try {
      const ip = request.headers.get('CF-Connecting-IP') || 'local';
      const general = await env.REQUEST_LIMITER.limit({ key: ip });
      const planner = endpoint === 'plan-trip' && await env.PLAN_LIMITER.limit({ key: ip });
      if (!general.success || (planner && !planner.success)) return respond(errorResponse(429, 'Too many requests. Try again shortly.', { 'Retry-After': '60' }));
    } catch { return respond(errorResponse(503, 'Request limiter is unavailable')); }

    const ttl = ENDPOINTS[endpoint].ttl;
    // No BKK key in this URL, cache entry or client response.
    const cacheKey = new Request(url.origin + '/cache-v3/' + endpoint + '?' + params.toString());
    const cache = globalThis.caches?.default;
    if (ttl && cache) {
      try { const cached = await cache.match(cacheKey); if (cached) return respond(cached, 'HIT'); } catch { /* Continue without cache. */ }
    }
    const controller = new AbortController(), timeout = setTimeout(() => controller.abort(), 15000);
    let stage = 'emma-details';
    try {
      if (endpoint === 'arrivals-and-departures-for-stops') {
        const data = await batchBoards(params, env.BKK_API_KEY, controller.signal);
        const response = Response.json(data);
        if (cache && !data.data.failedStopIds.length) {
          const stored = response.clone(); stored.headers.set('Cache-Control', 'public, max-age=10');
          ctx.waitUntil(cache.put(cacheKey, stored).catch(() => {}));
        }
        return respond(response, 'MISS');
      }
      const details = await emmaDetails(endpoint, params, url.origin, ctx);
      if (details) return respond(Response.json(details), 'BYPASS');
      if (endpoint === 'vehicles-for-route' && params.get('routeId').split(',').every(id => /^emma:1:[A-Za-z0-9_.-]+$/.test(id))) {
        const result = await supplement({ status: 'OK', code: 200, version: 4, currentTime: Date.now(), data: { list: [], references: {} } }, endpoint, params, url.origin, ctx);
        return respond(Response.json(result), 'BYPASS');
      }
      const upstreamParams = new URLSearchParams(params);
      upstreamParams.delete('compact');
      upstreamParams.set('key', env.BKK_API_KEY);
      stage = 'bkk-fetch';
      const upstream = await fetch(UPSTREAM + endpoint + '.json?' + upstreamParams, {
        headers: { Accept: 'application/json' }, signal: controller.signal, redirect: 'manual'
      });
      if (!upstream.ok) {
        const status = upstream.status === 429 ? 429 : 502;
        return respond(errorResponse(status, 'BKK is temporarily unavailable', status === 429 ? { 'Retry-After': '60' } : {}));
      }
      stage = 'bkk-body';
      const body = await upstream.text();
      if (body.length > 3_000_000) return respond(errorResponse(502, 'BKK response is too large. Use a smaller query.'));
      stage = 'bkk-json';
      const parsed = JSON.parse(body);
      const primaryData = sanitize(endpoint === 'plan-trip' && params.get('compact') === 'true' ? compactPlan(parsed) : parsed, env.BKK_API_KEY);
      stage = 'emma-supplement';
      const data = await supplement(primaryData, endpoint, params, url.origin, ctx);
      // A train search can find vehicles outside the BKK viewport. Prefer BKK's
      // exact-ID response whenever it contains those same vehicles.
      const found = data.data?.entry?.vehicleIds || [];
      if (endpoint === 'search' && found.length) {
        const primaryVehicles = [];
        for (let offset = 0; offset < found.length; offset += 10) {
          const lookup = new URLSearchParams({ version: '4', key: env.BKK_API_KEY, vehicleId: found.slice(offset, offset + 10).join(','), includeReferences: 'routes' });
          try {
            const primaryResponse = await fetch(UPSTREAM + 'vehicles.json?' + lookup, { headers: { Accept: 'application/json' }, signal: controller.signal, redirect: 'manual' });
            if (!primaryResponse.ok) continue;
            const primary = sanitize(await primaryResponse.json(), env.BKK_API_KEY);
            if (primary.status !== 'OK') continue;
            Object.assign(data.data.references.routes, primary.data?.references?.routes);
            for (const vehicle of primary.data?.list || []) if (vehicle.location) primaryVehicles.push(vehicle);
          } catch { /* Retain EMMA's search result if BKK lookup is unavailable. */ }
        }
        const merged = mergeVehicles(primaryVehicles, found.map(id => data.data.references.vehicles[id]).filter(Boolean));
        data.data.entry.vehicleIds = merged.map(v => v.vehicleId);
        data.data.references.vehicles = Object.fromEntries(merged.map(v => [v.vehicleId, v]));
      }
      const response = Response.json(data);
      // API errors may arrive as HTTP 200. Never cache those or planner errors.
      if (ttl && cache && data.status === 'OK' && !data.data?.entry?.error) {
        // Keep primary data fast during an EMMA outage without hiding recovery
        // behind the much longer station/search cache lifetime.
        const effectiveTtl = data.fallback?.status === 'unavailable' ? Math.min(ttl, 5) : ttl;
        const stored = response.clone(); stored.headers.set('Cache-Control', `public, max-age=${effectiveTtl}`);
        ctx.waitUntil(cache.put(cacheKey, stored).catch(() => {}));
      }
      return respond(response, ttl ? 'MISS' : 'BYPASS');
    } catch (e) {
      console.warn(JSON.stringify({ event: 'proxy-failure', endpoint, stage, kind: e?.name || 'Error' }));
      // Do not return exception messages: fetch errors can contain the keyed URL.
      const emmaRequest = (params.get('tripId') || params.get('stopId') || '').startsWith('emma:');
      return respond(errorResponse(controller.signal.aborted ? 504 : 502, `Could not reach ${emmaRequest ? 'MÁV fallback' : 'BKK'}. Try again shortly.`));
    } finally { clearTimeout(timeout); }
  }
};
