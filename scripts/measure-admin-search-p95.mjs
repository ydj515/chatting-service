#!/usr/bin/env node
import { performance } from 'node:perf_hooks';
import { writeFile } from 'node:fs/promises';
import { buildRequestPlans } from './lib/adminMeasurePlan.mjs';
import { summarizeSamples } from './lib/adminLatencyStats.mjs';

const args = parseArgs(process.argv.slice(2));
const baseUrl = args['base-url'] ?? process.env.ADMIN_API_BASE_URL ?? 'http://localhost/api';
const token = args.token ?? process.env.CHAT_ADMIN_TOKEN ?? 'local-admin-token';
const scenario = scenarioValue(args.scenario ?? 'both');
const requests = positiveInteger(args.requests ?? '100', '--requests');
const warmup = nonNegativeInteger(args.warmup ?? '10', '--warmup');
const concurrency = positiveInteger(args.concurrency ?? '5', '--concurrency');
const roomId = positiveInteger(args['room-id'] ?? '30001', '--room-id');
const query = args.query ?? 'hello searchable admin keyword';
const limit = positiveInteger(args.limit ?? '50', '--limit');
const targetP95Ms = positiveInteger(args['target-p95-ms'] ?? '1000', '--target-p95-ms');
const output = args.output;
const from = args.from;
const to = args.to;

const plans = buildRequestPlans({ baseUrl, scenario, roomId, query, limit, from, to });
const results = [];
for (const plan of plans) {
  if (warmup > 0) {
    await runSamples(plan, warmup, Math.min(concurrency, warmup), token);
  }
  const samples = await runSamples(plan, requests, concurrency, token);
  results.push({
    ...plan,
    summary: summarizeSamples(samples, { targetP95Ms }),
  });
}

const report = {
  measuredAt: new Date().toISOString(),
  targetP95Ms,
  options: {
    baseUrl,
    scenario,
    requests,
    warmup,
    concurrency,
    roomId,
    query,
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
