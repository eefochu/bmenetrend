// Only the GTFS-RT fields used by warnings are decoded. Unknown fields/extensions are
// skipped by wire type; full vehicle positions and stop timing predictions stay upstream.
// Field numbers: https://github.com/google/transit/blob/master/gtfs-realtime/proto/gtfs-realtime.proto
const decoder = new TextDecoder('utf-8', { fatal: true });
function message(bytes, wanted) {
  let p = 0;
  const fields = new Map();
  function uint() {
    let n = 0, factor = 1;
    for (let i = 0; i < 10; i++) {
      if (p >= bytes.length) throw Error('Truncated protobuf');
      const b = bytes[p++];
      if (i === 9 && b > 1) throw Error('Invalid varint');
      n += (b & 127) * factor; factor *= 128;
      if (!(b & 128)) return Number.isSafeInteger(n) ? n : null;
    }
    throw Error('Invalid varint');
  }
  while (p < bytes.length) {
    const tag = uint();
    if (tag === null || tag < 8 || tag > 0xffffffff) throw Error('Invalid field');
    const field = Math.floor(tag / 8), wire = tag % 8;
    let value;
    if (wire === 0) value = uint();
    else if (wire === 2) {
      const size = uint();
      if (size === null || size > bytes.length - p) throw Error('Invalid length');
      value = bytes.subarray(p, p + size); p += size;
    } else if (wire === 1 || wire === 5) {
      p += wire === 1 ? 8 : 4;
      if (p > bytes.length) throw Error('Truncated fixed field');
      continue;
    } else throw Error('Unsupported wire type');
    if (wanted && !wanted.has(field)) continue;
    if (!fields.has(field)) fields.set(field, []);
    fields.get(field).push(value);
  }
  return fields;
}
const bytes = (m, k) => m.get(k)?.findLast(v => v instanceof Uint8Array);
const text = (m, k) => { const b = bytes(m, k); return b ? decoder.decode(b).slice(0, 16000) : undefined; };
const number = (m, k) => m.get(k)?.findLast(v => typeof v === 'number');
const children = (m, k, wanted) => (m.get(k) || []).filter(v => v instanceof Uint8Array).map(v => message(v, wanted));
const child = (m, k) => { const b = bytes(m, k); return b ? message(b) : new Map(); };
const translations = (m, k) => children(child(m, k), 1).map(t => ({ language: text(t, 2) || '', text: text(t, 1) || '' })).filter(t => t.text);
const descriptor = m => ({ tripId: text(m, 1), routeId: text(m, 5), directionId: number(m, 6),
  startTime: text(m, 2), startDate: text(m, 3), relationship: number(m, 4) ?? 0 });

const STOP_FIELDS = new Set([4, 5]);
export function decodeFeed(buffer, kind) {
  if (buffer.byteLength > 8_000_000) throw Error('Feed too large');
  const root = message(new Uint8Array(buffer)), header = child(root, 1);
  if (!['1', '1.0', '2.0'].includes(text(header, 1)) || (number(header, 2) ?? 0) !== 0) throw Error('Not a full GTFS feed');
  const timestamp = number(header, 3);
  if (!timestamp || timestamp > Date.now() / 1000 + 300) throw Error('Invalid feed timestamp');
  const entities = children(root, 2);
  if (entities.length > 20000) throw Error('Too many entities');
  const items = [];
  for (const entity of entities) {
    if (number(entity, 2)) continue;
    const id = text(entity, 1);
    if (!id) continue;
    if (kind === 'alerts' && bytes(entity, 5)) {
      const a = child(entity, 5);
      items.push({ id, periods: children(a, 1).map(p => ({ start: number(p, 1), end: number(p, 2) })),
        selectors: children(a, 5).map(s => ({ agencyId: text(s, 1), routeId: text(s, 2), routeType: number(s, 3),
          trip: bytes(s, 4) ? descriptor(child(s, 4)) : undefined, stopId: text(s, 5), directionId: number(s, 6) })),
        cause: number(a, 6), effect: number(a, 7), severity: number(a, 14),
        title: translations(a, 10), description: translations(a, 11), url: translations(a, 8) });
    } else if (kind === 'tripChanges' && bytes(entity, 3)) {
      const update = child(entity, 3), trip = descriptor(child(update, 1));
      const skippedStops = children(update, 2, STOP_FIELDS).filter(s => number(s, 5) === 1).map(s => text(s, 4)).filter(Boolean);
      if (trip.tripId && (trip.relationship === 3 || trip.relationship === 7 || skippedStops.length)) {
        items.push({ id, trip, cancelled: trip.relationship === 3 || trip.relationship === 7,
          skippedStops, updatedAt: number(update, 4) });
      }
    }
  }
  return { timestamp, items };
}

const flights = new Map();
async function feed(kind, env, origin) {
  const cache = globalThis.caches?.default;
  // Long cache lifetime preserves the last successful snapshot through a short outage;
  // fetchedAt controls freshness independently of Cloudflare's eviction policy.
  const key = new Request(origin + '/gtfs-cache-v1/' + kind);
  let old;
  try { old = await (await cache?.match(key))?.json(); } catch { /* Cache is optional. */ }
  const now = Date.now(), ttl = kind === 'alerts' ? 60_000 : 30_000;
  if (old && now - (old.checkedAt || old.fetchedAt) < (old.unavailable ? 15000 : ttl) && now >= (old.checkedAt || old.fetchedAt)) return old;
  const flightKey = origin + kind;
  if (!flights.has(flightKey)) flights.set(flightKey, (async () => {
    const controller = new AbortController(), timeout = setTimeout(() => controller.abort(), 12000);
    try {
      const name = kind === 'alerts' ? 'Alerts' : 'TripUpdates';
      const url = new URL('https://go.bkk.hu/api/query/v1/ws/gtfs-rt/full/' + name + '.pb');
      url.searchParams.set('key', env.BKK_API_KEY);
      const response = await fetch(url, { signal: controller.signal, redirect: 'manual', headers: { Accept: 'application/x-protobuf' } });
      if (!response.ok || Number(response.headers.get('Content-Length')) > 8_000_000) throw Error('Feed unavailable');
      // Bound the decompressed stream as well as Content-Length (feeds are gzip encoded).
      const reader = response.body.getReader(), chunks = []; let size = 0;
      try {
        while (true) {
          const { value, done } = await reader.read(); if (done) break;
          size += value.length;
          if (size > 8_000_000) throw Error('Feed too large');
          chunks.push(value);
        }
      } finally { await reader.cancel().catch(() => {}); }
      const all = new Uint8Array(size); let offset = 0;
      for (const chunk of chunks) { all.set(chunk, offset); offset += chunk.length; }
      const result = { ...decodeFeed(all, kind), fetchedAt: Date.now() };
      // Never replace useful data with a regressed snapshot.
      if (old && result.timestamp < old.timestamp) throw Error('Feed regressed');
      if (cache) await cache.put(key, Response.json(result, { headers: { 'Cache-Control': 'public, max-age=21600' } })).catch(() => {});
      return result;
    } catch {
      // No keyed URL, upstream error body or exception text reaches the client.
      const failed = old?.items && now - old.fetchedAt <= 21_600_000
        ? { ...old, unavailable: true, checkedAt: Date.now() } : { unavailable: true, checkedAt: Date.now() };
      // A provider outage must not turn every user's refresh into another upstream attempt.
      if (cache) await cache.put(key, Response.json(failed, { headers: { 'Cache-Control': 'public, max-age=' + (failed.items ? 21600 : 15) } })).catch(() => {});
      return failed;
    } finally { clearTimeout(timeout); }
  })().finally(() => flights.delete(flightKey)));
  return flights.get(flightKey);
}

export async function disruptions(env, origin) {
  const [alerts, tripChanges] = await Promise.all([feed('alerts', env, origin), feed('tripChanges', env, origin)]);
  return { status: 'OK', alerts, tripChanges };
}
