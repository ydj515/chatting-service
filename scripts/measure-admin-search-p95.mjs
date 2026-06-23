#!/usr/bin/env node
import { performance } from 'node:perf_hooks';
import { writeFile } from 'node:fs/promises';
import { buildGatePhases, buildRequestPlans } from './lib/adminMeasurePlan.mjs';
import { summarizeGateReport, summarizeGateSamples } from './lib/adminLatencyStats.mjs';

const args = parseArgs(process.argv.slice(2));
const baseUrl = args['base-url'] ?? process.env.ADMIN_API_BASE_URL ?? 'http://localhost/api';
const token = requiredAdminToken(args.token ?? process.env.CHAT_ADMIN_TOKEN);
const scenario = scenarioValue(args.scenario ?? 'both');
const gate = gateValue(args.gate ?? 'warm');
const gatePhases = buildGatePhases(gate);
const requests = positiveInteger(args.requests ?? '100', '--requests');
const warmup = nonNegativeInteger(args.warmup ?? '10', '--warmup');
const concurrency = positiveInteger(args.concurrency ?? '5', '--concurrency');
const roomId = positiveInteger(args['room-id'] ?? '30001', '--room-id');
const query = args.query ?? 'hello searchable admin keyword';
const searchMode = searchModeValue(args['search-mode'] ?? 'FTS');
const limit = positiveInteger(args.limit ?? '50', '--limit');
const targetWarmP95OptionName = args['target-warm-p95-ms'] !== undefined ? '--target-warm-p95-ms' : '--target-p95-ms';
const targetWarmP95Ms = positiveInteger(args['target-warm-p95-ms'] ?? args['target-p95-ms'] ?? '1000', targetWarmP95OptionName);
const targetColdP99Ms = positiveInteger(args['target-cold-p99-ms'] ?? '6000', '--target-cold-p99-ms');
const output = args.output;
const from = args.from;
const to = args.to;

const plans = buildRequestPlans({ baseUrl, scenario, roomId, query, searchMode, limit, from, to });
const results = [];
for (const plan of plans) {
  const gateResults = [];
  for (const gatePhase of gatePhases) {
    if (gatePhase === 'warm' && warmup > 0) {
      await runSamples(plan, warmup, Math.min(concurrency, warmup), token);
    }
    const samples = await runSamples(plan, requests, concurrency, token);
    const targetMs = gatePhase === 'cold' ? targetColdP99Ms : targetWarmP95Ms;
    gateResults.push({
      gate: gatePhase,
      summary: summarizeGateSamples(samples, { gate: gatePhase, targetMs }),
    });
  }
  const finalGateResult = gateResults[gateResults.length - 1];
  results.push({
    ...plan,
    summary: finalGateResult.summary,
    gateResults,
  });
}
const gateSummary = summarizeGateReport(results);

const report = {
  measuredAt: new Date().toISOString(),
  gate,
  ok: gateSummary.ok,
  failedGates: gateSummary.failedGates,
  targetP95Ms: targetWarmP95Ms,
  targetWarmP95Ms,
  targetColdP99Ms,
  options: {
    baseUrl,
    scenario,
    gate,
    requests,
    warmup,
    concurrency,
    roomId,
    query,
    searchMode,
    limit,
    from: from ?? null,
    to: to ?? null,
  },
  results,
};

const serialized = JSON.stringify(report, null, 2);
if (output) {
  await writeFile(output, `${serialized}\n`);
}
console.log(serialized);

function parseArgs(argv) {
  const parsed = {};
  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i];
    if (!token.startsWith('--')) {
      throw new Error(`Unexpected argument: ${token}`);
    }
    const key = token.slice(2);
    const next = argv[i + 1];
    if (next === undefined || next.startsWith('--')) {
      parsed[key] = true;
    } else {
      parsed[key] = next;
      i += 1;
    }
  }
  return parsed;
}

function positiveInteger(value, name) {
  const parsed = Number.parseInt(String(value), 10);
  if (!Number.isSafeInteger(parsed) || parsed < 1 || String(value).match(/[^0-9]/)) {
    throw new Error(`${name} must be a positive integer.`);
  }
  return parsed;
}

function nonNegativeInteger(value, name) {
  const parsed = Number.parseInt(String(value), 10);
  if (!Number.isSafeInteger(parsed) || parsed < 0 || String(value).match(/[^0-9]/)) {
    throw new Error(`${name} must be a non-negative integer.`);
  }
  return parsed;
}

function scenarioValue(value) {
  if (value === 'history' || value === 'search' || value === 'both') {
    return value;
  }
  throw new Error('--scenario must be one of history, search, both.');
}

function gateValue(value) {
  if (value === 'warm' || value === 'cold' || value === 'both') {
    return value;
  }
  throw new Error('--gate must be one of warm, cold, both.');
}

function searchModeValue(value) {
  const normalized = String(value).trim().toUpperCase();
  if (normalized === 'FTS' || normalized === 'CONTAINS') {
    return normalized;
  }
  throw new Error('--search-mode must be one of FTS, CONTAINS.');
}

function requiredAdminToken(value) {
  const token = String(value ?? '').trim();
  if (token === '') {
    throw new Error('--token or CHAT_ADMIN_TOKEN is required.');
  }
  return token;
}

async function runSamples(plan, count, concurrency, token) {
  const samples = new Array(count);
  let nextIndex = 0;

  async function worker() {
    while (nextIndex < count) {
      const index = nextIndex;
      nextIndex += 1;
      samples[index] = await requestOnce(plan.url, token);
    }
  }

  await Promise.all(
    Array.from({ length: Math.min(concurrency, count) }, () => worker()),
  );
  return samples;
}

async function requestOnce(url, token) {
  const startedAt = performance.now();
  try {
    const response = await fetch(url, {
      headers: {
        'X-Admin-Token': token,
        Accept: 'application/json',
      },
    });
    await response.text();
    return {
      ok: response.ok,
      status: response.status,
      latencyMs: Math.round(performance.now() - startedAt),
    };
  } catch (error) {
    return {
      ok: false,
      status: 'NETWORK_ERROR',
      error: error.message,
      latencyMs: Math.round(performance.now() - startedAt),
    };
  }
}
