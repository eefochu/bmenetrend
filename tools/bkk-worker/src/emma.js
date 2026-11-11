// EMMA supplements missing railway entities only; BKK always wins a shared identity.
const ENDPOINT = 'https://mavplusz.hu/otp2-backend/otp/routers/default/index/graphql';
const RAIL = new Set(['RAIL', 'TRAMTRAIN']);
const ROUTE_FIELDS = 'gtfsId shortName longName mode color';
const STOP_FIELDS = 'gtfsId name lat lon vehicleMode platformCode locationType parentStation { gtfsId name }';
const VEHICLES_QUERY = `{vehiclePositions(swLat:45.7,swLon:16.1,neLat:48.6,neLon:22.9,modes:[RAIL,TRAMTRAIN]){
  vehicleId lat lon heading label lastUpdated speed trip { gtfsId tripShortName tripHeadsign route { ${ROUTE_FIELDS} } }
}}`;
const pending = new Map();
const backoff = new Map();
const clean = s => String(s || '').replace(/<[^>]*>/g, '').replace(/&#\d+;/g, '').trim();
export const normalize = s => clean(s).normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().replace(/[^a-z0-9]/g, '');
const bare = id => String(id || '').replace(/^emma:/, '').replace(/^(?:1:|BKK_|BKK:)/, '');
const stationCode = s => bare(s.parentStationId || s.id || s.gtfsId).replace(/_\d+$/, '');
const emmaId = id => 'emma:' + id;
const isRailStop = s => RAIL.has(s.type || s.vehicleMode) || /^BKK_00\d{7}(?:_\d+)?$/.test(s.id || '');
const position = v => v.location || v;
const validPoint = p => Number.isFinite(p?.lat) && Number.isFinite(p?.lon) && p.lat >= 45 && p.lat <= 49 && p.lon >= 16 && p.lon <= 23.5;
export function distance(a, b) {
  const rad = Math.PI / 180, dlat = (a.lat - b.lat) * rad, dlon = (a.lon - b.lon) * rad;
  const h = Math.sin(dlat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dlon / 2) ** 2;
  return 6371000 * 2 * Math.asin(Math.sqrt(Math.min(1, h)));
}
export function inArea(value, params) {
  const p = position(value), center = { lat: +params.get('lat'), lon: +params.get('lon') };
  if (!validPoint(p)) return false;
  if (params.has('radius')) return distance(p, center) <= +params.get('radius');
  return Math.abs(p.lat - center.lat) <= +params.get('latSpan') && Math.abs(p.lon - center.lon) <= +params.get('lonSpan');
}

async function graphql(query, variables, ttl, origin, ctx) {
  const body = JSON.stringify({ query, variables });
  // Hash the fixed query + validated variables. Never expose a general GraphQL proxy.
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(body));
  const hash = Array.from(new Uint8Array(digest), n => n.toString(16).padStart(2, '0')).join('');
  const key = new Request(origin + '/emma-cache-v1/' + hash), cache = globalThis.caches?.default;
  let cached;
  if (cache) { try { const hit = await cache.match(key); if (hit) cached = await hit.json(); } catch {} }
  if (cached?.__emmaUnavailable) throw new Error('EMMA retry cooldown');
  if (cached) return cached;
  if ((backoff.get(hash) || 0) > Date.now()) throw new Error('EMMA retry cooldown');
  if (pending.has(hash)) return pending.get(hash);
  const request = (async () => {
    const controller = new AbortController(), timer = setTimeout(() => controller.abort(), 6000);
    try {
      const response = await fetch(ENDPOINT, { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json', 'User-Agent': 'BMenetrendWorker/1.0 (rail fallback)', Origin: 'https://mavplusz.hu', Referer: 'https://mavplusz.hu/' }, body, signal: controller.signal, redirect: 'manual' });
      if (!response.ok) {
        const text = await response.text();
        console.warn(JSON.stringify({ event: 'emma-http', status: response.status, server: response.headers.get('server'), title: text.match(/<title>([^<]{0,100})<\/title>/i)?.[1] }));
        throw Object.assign(new Error('EMMA HTTP ' + response.status), { retrySeconds: response.status === 403 ? 60 : 10 });
      }
      const result = await response.json();
      if (result.errors?.length || !result.data) throw new Error('EMMA query failed');
      if (cache) ctx.waitUntil(cache.put(key, Response.json(result.data, { headers: { 'Cache-Control': 'public, max-age=' + ttl } })).catch(() => {}));
      return result.data;
    } finally { clearTimeout(timer); }
  })();
  pending.set(hash, request);
  try { const result = await request; backoff.delete(hash); return result; }
  catch (e) {
    console.warn(JSON.stringify({ event: 'emma-failure', kind: e?.name, message: String(e?.message || '').replace(/https?:\/\/\S+/g, '[URL]').slice(0, 180) }));
    const retrySeconds = e.retrySeconds || 10;
    backoff.set(hash, Date.now() + retrySeconds * 1000);
    if (cache) ctx.waitUntil(cache.put(key, Response.json({ __emmaUnavailable: true }, { headers: { 'Cache-Control': 'public, max-age=' + retrySeconds } })).catch(() => {}));
    if (backoff.size > 100) backoff.delete(backoff.keys().next().value);
    throw e;
  } finally { pending.delete(hash); }
}
function refs(data) {
  data.data ||= {}; data.data.references ||= {};
  for (const key of ['routes', 'stops', 'trips', 'vehicles']) data.data.references[key] ||= {};
  return data.data.references;
}
function route(r, references) {
  if (!r?.gtfsId || !RAIL.has(r.mode)) return null;
  const id = emmaId(r.gtfsId), plain = clean(r.shortName);
  references.routes[id] = { id, shortName: plain || clean(r.longName) || 'Train', description: clean(r.longName),
    type: r.mode, color: /^[a-f0-9]{6}$/i.test(r.color || '') ? r.color : '1666A8', textColor: 'FFFFFF', source: 'emma' };
  return id;
}
export function mapVehicle(v, references, now = Date.now() / 1000) {
  if (!v.vehicleId?.startsWith('1:') || !RAIL.has(v.trip?.route?.mode) || !validPoint(v) || !Number.isFinite(v.lastUpdated) || now - v.lastUpdated > 300 || now - v.lastUpdated < -60) return null;
  const routeId = route(v.trip.route, references);
  return { vehicleId: 'BKK_' + bare(v.vehicleId), source: 'emma', sourceId: v.vehicleId, routeId,
    tripId: v.trip.gtfsId ? emmaId(v.trip.gtfsId) : undefined, label: v.trip.tripHeadsign || v.label,
    trainName: clean(v.trip.tripShortName), trainNumber: v.trip.tripShortName?.match(/^\d+/)?.[0],
    location: { lat: v.lat, lon: v.lon }, bearing: v.heading, lastUpdateTime: v.lastUpdated, vehicleRouteType: v.trip.route.mode,
    speedKmh: Number.isFinite(v.speed) ? v.speed * 3.6 : undefined };
}
export function mergeVehicles(primary, fallback) {
  const result = [], ids = new Set();
  // Coupled vehicle sets can appear as one hyphenated BKK ID.
  for (const v of [...primary, ...fallback]) {
    if (!validPoint(position(v))) continue;
    const components = bare(v.vehicleId).split('-');
    if (components.some(id => ids.has(id))) continue;
    result.push(v); for (const id of components) ids.add(id);
  }
  return result;
}
export function mapStop(s) {
  if (!s.gtfsId?.startsWith('1:') || !RAIL.has(s.vehicleMode) || !validPoint(s)) return null;
  return { id: emmaId(s.gtfsId), source: 'emma', sourceId: s.gtfsId, name: s.name, lat: s.lat, lon: s.lon,
    type: s.vehicleMode, locationType: 0, parentStationId: s.parentStation?.gtfsId ? emmaId(s.parentStation.gtfsId) : undefined,
    platformCode: s.platformCode, style: { colors: ['1666A8'] } };
}
export function mergeStops(primary, fallback) {
  const result = [...new Map(primary.map(s => [s.id, s])).values()];
  for (const s of fallback) {
    const duplicate = result.find(p => p.id === s.id || (isRailStop(p) && (
      stationCode(p) === stationCode(s) || (normalize(p.name) === normalize(s.name) && validPoint(p) && distance(p, s) < 150)
    )));
    if (!duplicate) result.push(s);
  }
  return result;
}
const snapshot = (origin, ctx) => graphql(VEHICLES_QUERY, {}, 10, origin, ctx);
const envelope = () => ({ status: 'OK', code: 200, text: 'OK', version: 4, currentTime: Date.now(), data: { references: {} } });
function sourceId(id) {
  if (!/^emma:1:[A-Za-z0-9_.-]+$/.test(id || '')) throw new Error('Invalid EMMA identifier');
  return id.slice(5);
}

// Namespaced details go straight to EMMA; no accidental BKK lookup for a MÁV ID.
export async function emmaDetails(endpoint, params, origin, ctx) {
  const field = endpoint === 'trip-details' ? 'tripId' : 'stopId';
  if (!['trip-details', 'arrivals-and-departures-for-stop'].includes(endpoint) || !params.get(field)?.startsWith('emma:')) return null;
  const id = sourceId(params.get(field)), data = envelope(), references = refs(data);
  if (endpoint === 'trip-details') {
    const query = `query($id:String!){trip(id:$id){gtfsId tripHeadsign geometry route{${ROUTE_FIELDS}} stops{${STOP_FIELDS}}
      stoptimes{scheduledArrival realtimeArrival scheduledDeparture realtimeDeparture realtime serviceDay}}}`;
    const { trip } = await graphql(query, { id }, 30, origin, ctx);
    if (!trip || trip.gtfsId !== id || !RAIL.has(trip.route?.mode)) throw new Error('Rail trip not found');
    route(trip.route, references);
    const stopTimes = (trip.stops || []).map((s, i) => {
      const mapped = mapStop(s); if (!mapped) return null;
      references.stops[mapped.id] = mapped;
      const time = trip.stoptimes?.[i] || {}, day = time.serviceDay;
      const epoch = key => Number.isFinite(day) && Number.isFinite(time[key]) ? day + time[key] : undefined;
      return { stopId: mapped.id, arrivalTime: epoch('scheduledArrival'), departureTime: epoch('scheduledDeparture'),
        predictedArrivalTime: time.realtime ? epoch('realtimeArrival') : undefined, predictedDepartureTime: time.realtime ? epoch('realtimeDeparture') : undefined };
    }).filter(Boolean);
    // EMMA geometry is [longitude, latitude]; the test page consumes lat/lon pairs.
    data.data.entry = { stopTimes, geometry: (trip.geometry || []).filter(p => Array.isArray(p) && validPoint({ lat: p[1], lon: p[0] })).map(p => [p[1], p[0]]) };
  } else {
    const start = Math.floor(Date.now() / 1000) - +(params.get('minutesBefore') || 0) * 60;
    const range = (+(params.get('minutesBefore') || 0) + +(params.get('minutesAfter') || 45)) * 60;
    const query = `query($id:String!,$start:Long!,$range:Int!){stop(id:$id){${STOP_FIELDS}
      stoptimesWithoutPatterns(startTime:$start,timeRange:$range,numberOfDepartures:50,omitNonPickups:true){
        scheduledDeparture realtimeDeparture realtime serviceDay headsign trip{gtfsId tripShortName tripHeadsign route{${ROUTE_FIELDS}}}}}}`;
    const { stop } = await graphql(query, { id, start: Math.floor(start / 10) * 10, range }, 10, origin, ctx);
    if (!stop || stop.gtfsId !== id || !RAIL.has(stop.vehicleMode)) throw new Error('Rail stop not found');
    const mapped = mapStop(stop); references.stops[mapped.id] = mapped;
    const times = new Map();
    for (const st of stop.stoptimesWithoutPatterns || []) {
      const routeId = route(st.trip?.route, references); if (!routeId || !st.trip?.gtfsId || !Number.isFinite(st.serviceDay) || !Number.isFinite(st.scheduledDeparture)) continue;
      const tripId = emmaId(st.trip.gtfsId), departureTime = st.serviceDay + st.scheduledDeparture;
      const predictedDepartureTime = st.realtime && Number.isFinite(st.realtimeDeparture) ? st.serviceDay + st.realtimeDeparture : undefined;
      if ((predictedDepartureTime ?? departureTime) < start || (predictedDepartureTime ?? departureTime) > start + range) continue;
      references.trips[tripId] = { id: tripId, routeId, tripShortName: st.trip.tripShortName };
      times.set(tripId + ':' + departureTime, { tripId, stopId: mapped.id, departureTime, predictedDepartureTime, stopHeadsign: st.headsign || st.trip.tripHeadsign });
    }
    data.data.entry = { stopTimes: [...times.values()].sort((a, b) => (a.predictedDepartureTime ?? a.departureTime) - (b.predictedDepartureTime ?? b.departureTime)).slice(0, +(params.get('limit') || 12)) };
  }
  data.fallback = { source: 'emma', status: 'ok' };
  return data;
}

export async function supplement(data, endpoint, params, origin, ctx) {
  if (data.status !== 'OK' || !['vehicles-for-location', 'vehicles', 'vehicles-for-route', 'stops-for-location', 'search'].includes(endpoint)) return data;
  const references = refs(data);
  try {
    let added = 0;
    if (['vehicles-for-location', 'vehicles', 'vehicles-for-route', 'search'].includes(endpoint)) {
      const ids = (params.get('routeId') || '').split(',');
      const railRoute = ids.some(id => id.startsWith('emma:1:') || /^BKK_(?:[SZG]\d|IC|IR|EC|EN|rj|Sz)/.test(id));
      const searchText = normalize(params.get('query'));
      if (endpoint !== 'vehicles-for-route' || railRoute) {
        const { vehiclePositions } = await snapshot(origin, ctx);
        let vehicles = (vehiclePositions || []).map(v => mapVehicle(v, references)).filter(Boolean);
        if (endpoint === 'vehicles-for-location') vehicles = vehicles.filter(v => inArea(v, params));
        if (endpoint === 'vehicles') {
          const wanted = new Set((params.get('vehicleId') || '').split(',').map(bare));
          vehicles = vehicles.filter(v => wanted.has(bare(v.vehicleId)));
        }
        if (endpoint === 'vehicles-for-route') vehicles = vehicles.filter(v => ids.includes(v.routeId) || ids.some(id => bare(id) === references.routes[v.routeId]?.shortName));
        if (endpoint === 'search') {
          vehicles = vehicles.filter(v => [v.trainNumber, v.trainName, v.vehicleId, v.label, references.routes[v.routeId]?.shortName].some(t => normalize(t).includes(searchText)));
          // Query BKK for matching vehicles outside the view before using EMMA versions.
          // These are hydrated by the worker with the primary source in preferBkkVehicles.
          vehicles = mergeVehicles([], vehicles).slice(0, 20);
          data.data.entry ||= {}; data.data.entry.vehicleIds = vehicles.map(v => v.vehicleId);
          for (const v of vehicles) references.vehicles[v.vehicleId] = v;
          added += vehicles.length;
        } else {
          const merged = mergeVehicles(data.data.list || [], vehicles);
          added += merged.length - (data.data.list || []).length; data.data.list = merged;
        }
      }
    }
    if (endpoint === 'stops-for-location' || (endpoint === 'search' && params.get('query').trim().length >= 2 && !/^\d+$/.test(params.get('query')))) {
      let raw;
      if (endpoint === 'search') {
        const result = await graphql(`query($name:String!){stops(name:$name){${STOP_FIELDS}}}`, { name: params.get('query') }, 300, origin, ctx);
        raw = result.stops;
      } else {
        const lat = +params.get('lat'), lon = +params.get('lon'), radius = +params.get('radius');
        const latSpan = radius ? radius / 110000 : +params.get('latSpan'), lonSpan = radius ? radius / (110000 * Math.cos(lat * Math.PI / 180)) : +params.get('lonSpan');
        const result = await graphql(`query($s:Float!,$w:Float!,$n:Float!,$e:Float!){stopsByBbox(minLat:$s,minLon:$w,maxLat:$n,maxLon:$e){${STOP_FIELDS}}}`,
          { s: lat - latSpan, w: lon - lonSpan, n: lat + latSpan, e: lon + lonSpan }, 300, origin, ctx);
        raw = result.stopsByBbox;
      }
      let stops = (raw || []).map(mapStop).filter(Boolean);
      if (endpoint === 'stops-for-location') stops = stops.filter(s => inArea(s, params));
      const primary = endpoint === 'search' ? (data.data.entry?.stopIds || []).map(id => references.stops[id]).filter(Boolean) : data.data.list || [];
      const merged = mergeStops(primary, stops);
      added += merged.length - primary.length;
      if (endpoint === 'search') {
        for (const s of merged) references.stops[s.id] = s;
        data.data.entry.stopIds = merged.map(s => s.id);
      } else data.data.list = merged;
    }
    data.fallback = { source: 'emma', status: 'ok', added };
  } catch { data.fallback = { source: 'emma', status: 'unavailable', message: 'MÁV fallback unavailable; showing BKK data.' }; }
  return data;
}
