import fs from 'node:fs';
import path from 'node:path';

const BASE_URL = process.env.WORKER_URL || 'https://bmenetrend-bkk-proxy.bmenetrend-backend.workers.dev';
const REPORT_DIR = process.env.REPORT_DIR || '/private/tmp/bkk-gemini-validation-v2';

fs.mkdirSync(REPORT_DIR, { recursive: true });

function percentile(arr, p) {
  if (arr.length === 0) return 0;
  const sorted = [...arr].sort((a, b) => a - b);
  if (sorted.length === 1) return sorted[0];
  const index = (p / 100) * (sorted.length - 1);
  const lower = Math.floor(index);
  const upper = Math.ceil(index);
  const weight = index - lower;
  return Math.round((sorted[lower] * (1 - weight) + sorted[upper] * weight) * 10) / 10;
}

const sleep = ms => new Promise(r => setTimeout(r, ms));

let totalActualCalls = 0;
let globalRateLimitHit = false;

async function measureRequest(endpointPath, validator) {
  if (globalRateLimitHit) {
    return {
      status: 429,
      ttfbMs: 0,
      bodyReadMs: 0,
      parseMs: 0,
      totalMs: 0,
      sizeBytes: 0,
      proxyCache: 'ABORTED',
      cfCacheStatus: null,
      fallbackStatus: 'error',
      isSuccess: false,
      validationError: 'Skipped due to prior HTTP 429 rate limit',
      isRateLimited: true
    };
  }

  const url = `${BASE_URL}${endpointPath}`;
  totalActualCalls++;

  const start = performance.now();
  let res;
  try {
    res = await fetch(url, {
      headers: {
        'Accept': 'application/json',
        'User-Agent': 'BMENetrend-Benchmarker/2.0'
      }
    });
  } catch (err) {
    const ttfb = performance.now() - start;
    return {
      status: 0,
      ttfbMs: Math.round(ttfb * 10) / 10,
      bodyReadMs: 0,
      parseMs: 0,
      totalMs: Math.round(ttfb * 10) / 10,
      sizeBytes: 0,
      proxyCache: 'ERROR',
      cfCacheStatus: null,
      fallbackStatus: 'error',
      isSuccess: false,
      validationError: `Network error: ${err.message}`,
      isRateLimited: false
    };
  }

  const ttfb = performance.now() - start;

  if (res.status === 429) {
    globalRateLimitHit = true;
    console.error(`  [RATE LIMIT] HTTP 429 received on ${endpointPath}. Halting further requests!`);
    return {
      status: 429,
      ttfbMs: Math.round(ttfb * 10) / 10,
      bodyReadMs: 0,
      parseMs: 0,
      totalMs: Math.round(ttfb * 10) / 10,
      sizeBytes: 0,
      proxyCache: res.headers.get('x-proxy-cache') || 'UNKNOWN',
      cfCacheStatus: res.headers.get('cf-cache-status') || null,
      fallbackStatus: 'error',
      isSuccess: false,
      validationError: 'HTTP 429 Too Many Requests',
      isRateLimited: true
    };
  }

  const bodyStart = performance.now();
  let bodyText = '';
  try {
    bodyText = await res.text();
  } catch (err) {
    const bodyRead = performance.now() - bodyStart;
    const total = performance.now() - start;
    return {
      status: res.status,
      ttfbMs: Math.round(ttfb * 10) / 10,
      bodyReadMs: Math.round(bodyRead * 10) / 10,
      parseMs: 0,
      totalMs: Math.round(total * 10) / 10,
      sizeBytes: 0,
      proxyCache: res.headers.get('x-proxy-cache') || 'UNKNOWN',
      cfCacheStatus: res.headers.get('cf-cache-status') || null,
      fallbackStatus: 'error',
      isSuccess: false,
      validationError: `Body read error: ${err.message}`,
      isRateLimited: false
    };
  }
  const bodyRead = performance.now() - bodyStart;
  const sizeBytes = Buffer.byteLength(bodyText, 'utf8');

  const parseStart = performance.now();
  let json = null;
  let parseError = null;
  try {
    json = JSON.parse(bodyText);
  } catch (err) {
    parseError = err.message;
  }
  const parse = performance.now() - parseStart;
  const total = performance.now() - start;

  const proxyCache = res.headers.get('x-proxy-cache') || 'UNKNOWN';
  const cfCacheStatus = res.headers.get('cf-cache-status') || null;

  // Fallback status absent means not-applicable, not EMMA ok
  const fallbackStatus = json?.fallback?.status || (json?.fallback ? 'none' : 'not-applicable');

  let validation = { ok: true };
  if (res.status !== 200) {
    validation = { ok: false, reason: `HTTP status ${res.status}` };
  } else if (parseError) {
    validation = { ok: false, reason: `JSON parse error: ${parseError}` };
  } else if (typeof validator === 'function') {
    validation = validator(json, res);
  }

  return {
    status: res.status,
    ttfbMs: Math.round(ttfb * 10) / 10,
    bodyReadMs: Math.round(bodyRead * 10) / 10,
    parseMs: Math.round(parse * 10) / 10,
    totalMs: Math.round(total * 10) / 10,
    sizeBytes,
    proxyCache,
    cfCacheStatus,
    fallbackStatus,
    isSuccess: validation.ok,
    validationError: validation.ok ? null : (validation.reason || 'Validation failed'),
    isRateLimited: false
  };
}

async function benchmarkEndpoint(config, repeatCount = 5) {
  const { name, path: endpointPath, validator } = config;
  console.log(`\nBenchmarking ${name}...`);
  console.log(`  Path: ${endpointPath}`);

  const samples = [];

  // Sample 1: Initial request (record actual cache state observed, warm if HIT, cold if MISS)
  const initial = await measureRequest(endpointPath, validator);
  samples.push({ index: 1, type: 'initial', ...initial });
  console.log(`  Sample 1 (Initial): total=${initial.totalMs}ms (ttfb=${initial.ttfbMs}ms, body=${initial.bodyReadMs}ms, parse=${initial.parseMs}ms) | cache=${initial.proxyCache} | status=${initial.status} | fallback=${initial.fallbackStatus} | success=${initial.isSuccess}`);

  if (globalRateLimitHit) {
    return buildEndpointResult(name, endpointPath, samples);
  }

  await sleep(250);

  // Samples 2..N: Repeat requests against the EXACT SAME path
  for (let i = 0; i < repeatCount; i++) {
    if (globalRateLimitHit) break;
    const sample = await measureRequest(endpointPath, validator);
    samples.push({ index: i + 2, type: 'repeat', ...sample });
    console.log(`  Sample ${i + 2} (Repeat ${i + 1}/${repeatCount}): total=${sample.totalMs}ms (ttfb=${sample.ttfbMs}ms, body=${sample.bodyReadMs}ms, parse=${sample.parseMs}ms) | cache=${sample.proxyCache} | status=${sample.status} | success=${sample.isSuccess}`);
    await sleep(200);
  }

  return buildEndpointResult(name, endpointPath, samples);
}

function buildEndpointResult(name, endpointPath, samples) {
  const hitSamples = samples.filter(s => s.isSuccess && s.proxyCache === 'HIT');
  const missSamples = samples.filter(s => s.isSuccess && s.proxyCache === 'MISS');
  const bypassSamples = samples.filter(s => s.isSuccess && s.proxyCache === 'BYPASS');
  const errorSamples = samples.filter(s => !s.isSuccess);

  const hitTotals = hitSamples.map(s => s.totalMs);
  const hitTtfbs = hitSamples.map(s => s.ttfbMs);
  const hitBodyReads = hitSamples.map(s => s.bodyReadMs);
  const hitParses = hitSamples.map(s => s.parseMs);

  const avg = arr => arr.length ? Math.round((arr.reduce((a, b) => a + b, 0) / arr.length) * 10) / 10 : 0;

  const hitMetrics = hitSamples.length > 0 ? {
    sampleCount: hitSamples.length,
    p50TotalMs: percentile(hitTotals, 50),
    p95TotalMs: percentile(hitTotals, 95),
    minTotalMs: Math.min(...hitTotals),
    maxTotalMs: Math.max(...hitTotals),
    avgTotalMs: avg(hitTotals),
    p50TtfbMs: percentile(hitTtfbs, 50),
    p95TtfbMs: percentile(hitTtfbs, 95),
    avgTtfbMs: avg(hitTtfbs),
    avgBodyReadMs: avg(hitBodyReads),
    avgParseMs: avg(hitParses),
    avgSizeBytes: Math.round(hitSamples.reduce((acc, s) => acc + s.sizeBytes, 0) / hitSamples.length),
    targetMet100ms: percentile(hitTotals, 95) <= 100.0
  } : null;

  const missMetrics = missSamples.length > 0 ? {
    sampleCount: missSamples.length,
    avgTotalMs: avg(missSamples.map(s => s.totalMs)),
    minTotalMs: Math.min(...missSamples.map(s => s.totalMs)),
    maxTotalMs: Math.max(...missSamples.map(s => s.totalMs)),
    avgTtfbMs: avg(missSamples.map(s => s.ttfbMs)),
    avgSizeBytes: Math.round(missSamples.reduce((acc, s) => acc + s.sizeBytes, 0) / missSamples.length)
  } : null;

  return {
    name,
    endpointPath,
    totalCalls: samples.length,
    samples,
    hitMetrics,
    missMetrics,
    counts: {
      total: samples.length,
      hit: hitSamples.length,
      miss: missSamples.length,
      bypass: bypassSamples.length,
      error: errorSamples.length
    },
    allSuccessful: errorSamples.length === 0,
    observedCacheStates: [...new Set(samples.map(s => s.proxyCache))],
    observedFallbackStatuses: [...new Set(samples.map(s => s.fallbackStatus))]
  };
}

async function runAllBenchmarks() {
  const startTime = new Date();
  console.log(`================================================================`);
  console.log(`Starting Independent BKK Worker Performance Re-Check`);
  console.log(`Target URL: ${BASE_URL}`);
  console.log(`Start Time: ${startTime.toISOString()}`);
  console.log(`================================================================`);

  const benchmarkConfigs = [
    // 1. Vehicles for Location
    {
      name: 'Vehicles for Location',
      path: '/api/vehicles-for-location.json?lat=47.4979&lon=19.0402&latSpan=0.03&lonSpan=0.03&includeReferences=routes',
      validator: (json) => {
        if (json?.status !== 'OK') return { ok: false, reason: `Status not OK: ${json?.status}` };
        if (!Array.isArray(json?.data?.list) || json.data.list.length === 0) {
          return { ok: false, reason: 'Missing or empty vehicles list' };
        }
        return { ok: true };
      }
    },
    // 2. Station Search
    {
      name: 'Station Search',
      path: '/api/search.json?query=Keleti&includeReferences=stops,routes',
      validator: (json) => {
        if (json?.status !== 'OK') return { ok: false, reason: `Status not OK: ${json?.status}` };
        const stopIds = json?.data?.entry?.stopIds;
        if (!Array.isArray(stopIds) || stopIds.length === 0) {
          return { ok: false, reason: 'Missing or empty stopIds in search entry' };
        }
        return { ok: true };
      }
    },
    // 3. Stops for Location
    {
      name: 'Stops for Location',
      path: '/api/stops-for-location.json?lat=47.4979&lon=19.0402&latSpan=0.01&lonSpan=0.01',
      validator: (json) => {
        if (json?.status !== 'OK') return { ok: false, reason: `Status not OK: ${json?.status}` };
        if (!Array.isArray(json?.data?.list) || json.data.list.length === 0) {
          return { ok: false, reason: 'Missing or empty stops list' };
        }
        return { ok: true };
      }
    },
    // 4. Arrivals & Departures for Stop
    {
      name: 'Arrivals & Departures for Stop',
      path: '/api/arrivals-and-departures-for-stop.json?stopId=BKK_F01037&includeReferences=routes,trips',
      validator: (json) => {
        if (json?.status !== 'OK') return { ok: false, reason: `Status not OK: ${json?.status}` };
        const stopTimes = json?.data?.entry?.stopTimes;
        if (!Array.isArray(stopTimes) || stopTimes.length === 0) {
          return { ok: false, reason: 'Missing or empty stopTimes in departures entry' };
        }
        return { ok: true };
      }
    },
    // 5. Trip Planner (Lightweight Android Client Parameters)
    {
      name: 'Trip Planner (Lightweight Client)',
      path: '/api/plan-trip.json?fromPlace=Deak::47.4979,19.0541&toPlace=Keleti::47.5002,19.0838&mode=TRANSIT,WALK&numItineraries=3&includeReferences=false&showIntermediateStops=true',
      validator: (json) => {
        if (json?.status !== 'OK') return { ok: false, reason: `Status not OK: ${json?.status}` };
        if (json?.data?.entry?.error || json?.data?.entry?.plan?.error) {
          return { ok: false, reason: `Planner returned error entry: ${JSON.stringify(json?.data?.entry?.error || json?.data?.entry?.plan?.error)}` };
        }
        const itineraries = json?.data?.entry?.plan?.itineraries || json?.data?.entry?.itineraries;
        if (!Array.isArray(itineraries) || itineraries.length === 0) {
          return { ok: false, reason: 'Missing or empty itineraries' };
        }
        return { ok: true };
      }
    }
  ];

  const results = [];
  for (const config of benchmarkConfigs) {
    if (globalRateLimitHit) {
      console.warn(`Skipping remaining benchmarks due to rate limit`);
      break;
    }
    const res = await benchmarkEndpoint(config, 5);
    results.push(res);
    await sleep(400);
  }

  // Measure full default planner payload once for documentation comparison (without conflating)
  let defaultPlannerPayload = null;
  if (!globalRateLimitHit && totalActualCalls < 38) {
    console.log(`\nMeasuring reference default planner payload size...`);
    const defaultPlannerPath = '/api/plan-trip.json?fromPlace=Deak::47.4979,19.0541&toPlace=Keleti::47.5002,19.0838&mode=TRANSIT,WALK';
    const defaultSample = await measureRequest(defaultPlannerPath, (json) => {
      const itineraries = json?.data?.entry?.plan?.itineraries || json?.data?.entry?.itineraries;
      return { ok: json?.status === 'OK' && Array.isArray(itineraries) && itineraries.length > 0 };
    });
    defaultPlannerPayload = {
      path: defaultPlannerPath,
      sizeBytes: defaultSample.sizeBytes,
      sizeKB: Math.round(defaultSample.sizeBytes / 1024 * 10) / 10,
      status: defaultSample.status,
      proxyCache: defaultSample.proxyCache
    };
    console.log(`  Default Planner Payload: ${defaultPlannerPayload.sizeKB} KB (${defaultPlannerPayload.sizeBytes} bytes)`);
  }

  const endTime = new Date();

  // Generate Reports
  const jsonReportPath = path.join(REPORT_DIR, 'report.json');
  const mdReportPath = path.join(REPORT_DIR, 'report.md');

  const reportData = {
    timestamp: endTime.toISOString(),
    benchmarkDurationMs: endTime.getTime() - startTime.getTime(),
    workerUrl: BASE_URL,
    workerVersion: '468cd089-241b-4b61-a075-acd5789aa058',
    totalActualHttpCalls: totalActualCalls,
    rateLimitEncountered: globalRateLimitHit,
    performanceTargetMet: results.every(r => r.hitMetrics && r.hitMetrics.targetMet100ms),
    defaultPlannerComparison: defaultPlannerPayload,
    results
  };

  fs.writeFileSync(jsonReportPath, JSON.stringify(reportData, null, 2));

  let md = `# Deployed BKK Worker Independent Performance Re-Check Report (v2)\n\n`;
  md += `**Date:** ${endTime.toISOString()}\n`;
  md += `**Target URL:** \`${BASE_URL}\`\n`;
  md += `**Worker Deployed Version:** \`468cd089-241b-4b61-a075-acd5789aa058\`\n`;
  md += `**Total Actual HTTP Calls Executed:** ${totalActualCalls}\n`;
  md += `**Rate Limit (HTTP 429) Triggered:** ${globalRateLimitHit ? 'YES (FAILURE)' : 'NO'}\n\n`;

  md += `## Critical Timing Methodology & Corrections\n\n`;
  md += `1. **Complete Usable JSON Timing:** Previous benchmark captured latency at \`fetch()\` resolution (TTFB/headers only, ~22-29 ms), omitting body download and JSON parsing. In v2, timing measures the full pipeline: **TTFB (headers)**, **body read**, and **JSON parsing**. The **Total Duration (\`totalMs\`)** is now the primary performance metric.\n`;
  md += `2. **Accurate Payload Sizing:** Payload size is computed as actual UTF-8 bytes using \`Buffer.byteLength(bodyText, 'utf8')\` rather than UTF-16 character length.\n`;
  md += `3. **Query Consistency & Observed Cache State:** Initial and repeat samples now use the **EXACT SAME nonempty query** (avoiding artificial empty-query requests like \`Nyugati_<rand>\`). Cache states are labeled as observed (\`HIT\`, \`MISS\`, \`BYPASS\`) without assuming cold misses.\n`;
  md += `4. **Strict Response Validation:** Success strictly requires HTTP 200, valid JSON parsing, non-empty data arrays (vehicles list, stops list, search \`stopIds\`, departures \`stopTimes\`, planner \`itineraries\`), and no planner \`error\` objects. Fast errors are never counted as successes.\n`;
  md += `5. **Fallback Status Semantics:** Absent fallback status is correctly classified as \`not-applicable\`, not \`EMMA ok\`.\n`;
  md += `6. **Accurate Call Accounting:** Report counts exact total HTTP calls made (${totalActualCalls} total calls, strictly under 40-call budget).\n\n`;

  md += `## Cached HIT Latency Summary (Target: p95 Total <= 100 ms)\n\n`;
  md += `| Endpoint | Cache State Observed | HIT Samples | Total p50 | Total p95 | TTFB p50 | Body Read (Avg) | Parse (Avg) | Payload Size | Fallback Status | Target (p95 <= 100ms) |\n`;
  md += `| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |\n`;

  for (const r of results) {
    const states = r.observedCacheStates.join('/');
    const hit = r.hitMetrics;
    const fallback = r.observedFallbackStatuses.join(', ');
    if (hit) {
      const p50Str = `**${hit.p50TotalMs} ms**`;
      const p95Str = `**${hit.p95TotalMs} ms**`;
      const ttfbStr = `${hit.p50TtfbMs} ms`;
      const bodyStr = `${hit.avgBodyReadMs} ms`;
      const parseStr = `${hit.avgParseMs} ms`;
      const sizeStr = `${(hit.avgSizeBytes / 1024).toFixed(1)} KB`;
      const targetStr = hit.targetMet100ms ? '✅ PASS' : '❌ FAIL';
      md += `| **${r.name}** | \`${states}\` | ${r.counts.hit}/${r.counts.total} | ${p50Str} | ${p95Str} | ${ttfbStr} | ${bodyStr} | ${parseStr} | ${sizeStr} | \`${fallback}\` | ${targetStr} |\n`;
    } else {
      md += `| **${r.name}** | \`${states}\` | 0/${r.counts.total} (MISS/ERR) | N/A | N/A | N/A | N/A | N/A | N/A | \`${fallback}\` | ⚠️ No HITs |\n`;
    }
  }

  md += `\n## Cold / MISS Sample Summary (Honest Network Baseline)\n\n`;
  md += `| Endpoint | MISS Samples | MISS Total (Avg) | MISS TTFB (Avg) | MISS Payload Size |\n`;
  md += `| :--- | :--- | :--- | :--- | :--- |\n`;
  for (const r of results) {
    if (r.missMetrics) {
      md += `| **${r.name}** | ${r.missMetrics.sampleCount} | ${r.missMetrics.avgTotalMs} ms | ${r.missMetrics.avgTtfbMs} ms | ${(r.missMetrics.avgSizeBytes / 1024).toFixed(1)} KB |\n`;
    } else {
      md += `| **${r.name}** | 0 (Warm edge) | N/A | N/A | N/A |\n`;
    }
  }

  if (defaultPlannerPayload) {
    md += `\n## Planner Payload Optimization Comparison\n\n`;
    const lightResult = results.find(r => r.name.includes('Planner'));
    const lightSizeKB = lightResult?.hitMetrics ? (lightResult.hitMetrics.avgSizeBytes / 1024).toFixed(1) : 'N/A';
    md += `- **New Lightweight Android Request:** \`${lightSizeKB} KB\` (\`numItineraries=3\`, \`includeReferences=false\`, \`showIntermediateStops=true\`)\n`;
    md += `- **Default / Full Payload:** \`${defaultPlannerPayload.sizeKB} KB\` (default includes large full references)\n`;
    md += `- **Payload Reduction:** ~${lightSizeKB !== 'N/A' ? Math.round(defaultPlannerPayload.sizeKB - Number(lightSizeKB)) : 'N/A'} KB saved per request by omitting unneeded references.\n`;
  }

  md += `\n## Raw Sample Details\n\n`;
  for (const r of results) {
    md += `### ${r.name}\n`;
    md += `- **Path:** \`${r.endpointPath}\`\n`;
    md += `- **Total Calls:** ${r.totalCalls} (${r.counts.hit} HIT, ${r.counts.miss} MISS, ${r.counts.error} ERR)\n`;
    md += `- **Samples Table:**\n\n`;
    md += `| # | Type | Cache | HTTP | TTFB | Body Read | Parse | Total Ms | Size | Success | Validation |\n`;
    md += `| :- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |\n`;
    for (const s of r.samples) {
      md += `| ${s.index} | ${s.type} | \`${s.proxyCache}\` | ${s.status} | ${s.ttfbMs} ms | ${s.bodyReadMs} ms | ${s.parseMs} ms | **${s.totalMs} ms** | ${(s.sizeBytes / 1024).toFixed(1)} KB | ${s.isSuccess ? 'YES' : 'NO'} | ${s.validationError || 'OK'} |\n`;
    }
    md += `\n`;
  }

  md += `## Mobile Device Timing & Environmental Disclaimer\n\n`;
  md += `> [!IMPORTANT]\n`;
  md += `> **Lack of On-Device / Phone Timings:**\n`;
  md += `> All benchmark figures above reflect execution on a development workstation (macOS, Node.js 22 V8 engine) over broadband internet directly measuring Cloudflare Worker edge latency. These numbers do **not** reflect on-device performance on an actual Android smartphone.\n`;
  md += `> - **Cellular RTT:** 4G/5G mobile radio networks typically add 25–80 ms of latency.\n`;
  md += `> - **Mobile CPU / Deserialization:** Parsing a 960 KB – 1 MB JSON payload into Kotlin data classes via \`kotlinx.serialization\` on a mid-range or budget Android phone CPU typically requires **30–120 ms** of active CPU time and GC allocation, which cannot be measured from this Worker harness.\n`;

  fs.writeFileSync(mdReportPath, md);

  console.log(`\n================================================================`);
  console.log(`Benchmark completed successfully!`);
  console.log(`Reports saved to:`);
  console.log(`- JSON: ${jsonReportPath}`);
  console.log(`- Markdown: ${mdReportPath}`);
  console.log(`================================================================`);
}

runAllBenchmarks().catch(err => {
  console.error('Fatal benchmark error:', err);
  process.exit(1);
});
