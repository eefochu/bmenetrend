import { test } from 'node:test';
import assert from 'node:assert/strict';
import { decodeFeed } from './disruptions.js';
import worker from './index.js';
const join = (...parts) => Buffer.concat(parts.map(p => Buffer.from(p)));
const uint = n => { const b = []; n = BigInt(n); do { b.push(Number(n & 127n) | (n > 127n ? 128 : 0)); n >>= 7n; } while(n); return b; };
const value = (key, n) => join(uint(key * 8), uint(n));
const field = (key, bytes) => join(uint(key * 8 + 2), uint(Buffer.byteLength(bytes)), bytes);
const string = (key, text) => field(key, Buffer.from(text));
const now = Math.floor(Date.now() / 1000);
const header = (incrementality = 0) => field(1, join(string(1,'1.0'), value(2,incrementality), value(3,now)));
const translated = text => field(1,join(string(1,text),string(2,'hu')));
const trip = join(string(1,'daily-trip'),string(3,'20261114'),string(5,'0150'),value(4,3));
const notice = join(
  field(1,join(value(1,now-10),value(2,now+3600))),
  field(5,join(string(2,'0150'),string(5,'F01037'))),
  field(5,field(4,trip)),value(7,9),value(14,3),field(10,translated('Áthelyezett megálló')),
  field(11,translated('<p>Új megálló</p>')),field(8,translated('https://bkk.hu/bkk-info')),
  field(1001,join(value(1,123))));
const alerts = join(header(),field(2,join(string(1,'notice'),field(5,notice))));
const updates = join(header(),
  field(2,join(string(1,'cancel'),field(3,join(field(1,trip),value(4,now))))),
  field(2,join(string(1,'skip'),field(3,join(field(1,join(string(1,'other'),string(3,'20261114'))),
    field(2,join(string(4,'F01037'),value(5,1))),field(2,join(string(4,'F01038'),value(5,0))))))),
  field(2,join(string(1,'normal'),field(3,field(1,string(1,'normal'))))),
  field(2,join(string(1,'deleted'),value(2,1),field(3,field(1,trip)))),
  field(2,join(string(1,'shape'),field(6,string(1,'ignored')))));

test('decodes selectors, active periods, multilingual Unicode and unknown extensions', () => {
  const data = decodeFeed(alerts,'alerts');
  assert.equal(data.timestamp,now); assert.equal(data.items.length,1);
  assert.equal(data.items[0].title[0].text,'Áthelyezett megálló');
  assert.deepEqual(data.items[0].selectors[0],{agencyId:undefined,routeId:'0150',routeType:undefined,trip:undefined,stopId:'F01037',directionId:undefined});
  assert.equal(data.items[0].selectors[1].trip.startDate,'20261114');
  assert.equal(data.items[0].periods[0].end,now+3600);
});
test('only cancellations and skipped stops leave the server; normal timings/shapes/deletions are omitted', () => {
  const data = decodeFeed(updates,'tripChanges'); assert.equal(data.items.length,2);
  assert.equal(data.items[0].cancelled,true); assert.equal(data.items[0].trip.tripId,'daily-trip');
  assert.deepEqual(data.items[1].skippedStops,['F01037']);
  assert.equal(JSON.stringify(data).includes('normal'),false);
});
test('rejects truncated, malformed, oversized, differential and invalid timestamp feeds', () => {
  for (const bad of [alerts.subarray(0,alerts.length-1),Buffer.from([10,255]),Buffer.from([0]),join(header(1)),new Uint8Array(8_000_001),field(1,string(1,'1.0'))])
    assert.throws(() => decodeFeed(bad,'alerts'));
});
test('keyless endpoint shares feed calls/cache and preserves marked stale data on failures', async () => {
  const previousFetch=globalThis.fetch, previousCache=globalThis.caches, stored=new Map();let calls=0, fail=false;
  const secret='private-test-key';
  const env={BKK_API_KEY:secret,ALLOWED_ORIGINS:'*',REQUEST_LIMITER:{limit:async()=>({success:true})},PLAN_LIMITER:{limit:async()=>({success:true})}};
  const request=()=>worker.fetch(new Request('https://disruptions.test/api/service-disruptions.json'),env,{waitUntil:()=>{}});
  globalThis.caches={default:{match:async k=>stored.get(k.url)?.clone(),put:async(k,v)=>stored.set(k.url,v.clone())}};
  globalThis.fetch=async url=>{ calls++;assert.equal(url.origin,'https://go.bkk.hu');assert.equal(url.searchParams.get('key'),secret);
    if(fail) throw Error('Keyed URL: '+url);return new Response(url.pathname.includes('Alerts')?alerts:updates); };
  try {
    const responses=await Promise.all([request(),request(),request()]);assert.equal(calls,2);
    for(const r of responses){assert.equal(r.status,200);const text=await r.text();assert(!text.includes(secret));assert.equal(JSON.parse(text).alerts.items.length,1);}
    await request();assert.equal(calls,2);assert([...stored.keys()].every(k=>!k.includes(secret)));
    for(const[k,v]of stored){const j=await v.json();j.fetchedAt-=120000;stored.set(k,Response.json(j));}
    fail=true;const degraded=await(await request()).json();assert.equal(degraded.alerts.unavailable,true);assert.equal(degraded.alerts.items.length,1);
    assert.equal(degraded.tripChanges.unavailable,true);assert(!JSON.stringify(degraded).includes(secret));
    const beforeCooldown=calls;await request();assert.equal(calls,beforeCooldown,'Outage retries must share the negative cache');
    stored.clear();const empty=await(await request()).json();assert.equal(empty.alerts.items,undefined);assert.equal(empty.alerts.unavailable,true);
    const denied=await worker.fetch(new Request('https://disruptions.test/api/service-disruptions.json?key=x'),env,{});assert.equal(denied.status,400);
    env.REQUEST_LIMITER.limit=async()=>({success:false});assert.equal((await request()).status,429);
  }finally{globalThis.fetch=previousFetch;globalThis.caches=previousCache;}
});

test('real BKK protobuf snapshots decode official extension fields and daily cancellation identity', async () => {
  const {readFile}=await import('node:fs/promises');
  const alerts=decodeFeed(await readFile(new URL('./fixtures/Alerts.pb',import.meta.url)),'alerts');
  assert.equal(alerts.items.length,2);assert.equal(alerts.items[0].id,'bkkinfo-118288');
  assert.equal(alerts.items[0].selectors[0].routeId,'0150');
  assert.equal(alerts.items[0].title.find(t=>t.language==='hu').text,'megállóáthelyezés');
  assert(alerts.items[1].selectors.some(s=>s.trip?.tripId));
  const updates=decodeFeed(await readFile(new URL('./fixtures/TripUpdates.pb',import.meta.url)),'tripChanges');
  assert.equal(updates.items.length,1);assert.equal(updates.items[0].cancelled,true);
  assert.equal(updates.items[0].trip.startDate,'20261114');assert.equal(updates.items[0].trip.routeId,'0050');
});
